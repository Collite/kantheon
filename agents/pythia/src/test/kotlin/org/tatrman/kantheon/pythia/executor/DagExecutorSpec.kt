package org.tatrman.kantheon.pythia.executor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryStepRepository
import org.tatrman.kantheon.pythia.v1.DataDep
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import java.util.UUID

private fun dag(
    nodes: List<String>,
    edges: List<Pair<String, String>> = emptyList(),
    tests: Map<String, List<String>> = emptyMap(),
): PlanDag =
    PlanDag
        .newBuilder()
        .apply {
            nodes.forEach { id ->
                addNodes(PlanNode.newBuilder().setNodeId(id).addAllTestsHypIds(tests[id] ?: emptyList()))
            }
            edges.forEach { (f, t) ->
                addEdges(
                    DataDep
                        .newBuilder()
                        .setFromNodeId(f)
                        .setToNodeId(t)
                        .setBinding("b"),
                )
            }
        }.build()

private fun emitterWith(): Pair<EventEmitter, EventRepository> {
    val events = InMemoryEventRepository()
    return EventEmitter(events, RecordingNatsPublisher()) to events
}

/**
 * Stage 2.2 T2–T5 — the DAG executor: caps bound concurrency, priority orders the
 * batch, tiered failure handling (retry / INCONCLUSIVE / HALT), drain parks with
 * no post-drain launches and resumes without double-execution, and the execution
 * event vocabulary is emitted.
 */
class DagExecutorSpec :
    StringSpec({

        val id = UUID.randomUUID()

        "a diamond runs to completion respecting a per-investigation cap of 2" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock = MockNodeExecutor(delayMs = 5)
                val executor =
                    DagExecutor(
                        emitter,
                        InMemoryStepRepository(),
                        mock,
                        caps = ExecCaps(perInvestigation = 2, global = 8),
                    )
                val plan = dag(listOf("A", "B", "C", "D"), listOf("A" to "B", "A" to "C", "B" to "D", "C" to "D"))
                val outcome = executor.execute(id, plan, mutableSetOf(), HandleTable())
                outcome.shouldBeInstanceOf<ExecOutcome.Completed>()
                mock.executed shouldContainExactlyInAnyOrder listOf("A", "B", "C", "D")
                (mock.maxConcurrency.get() <= 2) shouldBe true
            }
        }

        "priority orders a ready batch (cap 1 → strictly sequential)" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock = MockNodeExecutor()
                val priority = mapOf("A" to 1.0, "B" to 3.0, "C" to 2.0)
                val executor =
                    DagExecutor(
                        emitter,
                        InMemoryStepRepository(),
                        mock,
                        caps = ExecCaps(perInvestigation = 1, perProvider = 1, global = 1),
                        priorityOf = { priority[it.nodeId] ?: 0.0 },
                    )
                executor.execute(id, dag(listOf("A", "B", "C")), mutableSetOf(), HandleTable())
                mock.executed shouldContainExactly listOf("B", "C", "A") // priority desc
            }
        }

        "a transient failure retries then succeeds" {
            runTest {
                val (emitter, events) = emitterWith()
                val mock = MockNodeExecutor(behavior = { NodeBehavior.TransientThenOk(times = 1) })
                val executor = DagExecutor(emitter, InMemoryStepRepository(), mock, RetryPolicy(baseDelayMs = 1))
                val outcome = executor.execute(id, dag(listOf("A")), mutableSetOf(), HandleTable())
                outcome.shouldBeInstanceOf<ExecOutcome.Completed>()
                mock.executed shouldContainExactly listOf("A")
                events.replay(id, 0L).map { it.kind }.contains("STEP_RETRYING") shouldBe true
            }
        }

        "a permanent failure marks the hypothesis INCONCLUSIVE and continues" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock =
                    MockNodeExecutor(
                        behavior = {
                            if (it.nodeId ==
                                "A"
                            ) {
                                NodeBehavior.FailKind(FailureKind.PERMANENT)
                            } else {
                                NodeBehavior.Ok
                            }
                        },
                    )
                val executor = DagExecutor(emitter, InMemoryStepRepository(), mock)
                val plan = dag(listOf("A", "B"), tests = mapOf("A" to listOf("H1")))
                val outcome = executor.execute(id, plan, mutableSetOf(), HandleTable())
                outcome.shouldBeInstanceOf<ExecOutcome.Completed>()
                executor.inconclusiveHypotheses shouldContainExactly setOf("H1")
                mock.executed shouldContainExactly listOf("B") // A failed, B still ran
            }
        }

        "a permanently-failed node prunes its dependents (never runs them on a missing handle)" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock =
                    MockNodeExecutor(
                        behavior = {
                            if (it.nodeId == "A") NodeBehavior.FailKind(FailureKind.PERMANENT) else NodeBehavior.Ok
                        },
                    )
                val executor = DagExecutor(emitter, InMemoryStepRepository(), mock)
                // B depends on A; A fails permanently → B must NOT run (its input never materialised).
                val plan = dag(listOf("A", "B"), listOf("A" to "B"), tests = mapOf("A" to listOf("H1")))
                val outcome = executor.execute(id, plan, mutableSetOf(), HandleTable())
                outcome.shouldBeInstanceOf<ExecOutcome.Completed>()
                mock.executed shouldContainExactly emptyList<String>()
                executor.inconclusiveHypotheses shouldContainExactly setOf("H1")
            }
        }

        "a systemic failure HALTs the investigation" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock = MockNodeExecutor(behavior = { NodeBehavior.FailKind(FailureKind.SYSTEMIC) })
                val executor = DagExecutor(emitter, InMemoryStepRepository(), mock)
                val outcome = executor.execute(id, dag(listOf("A")), mutableSetOf(), HandleTable())
                outcome.shouldBeInstanceOf<ExecOutcome.Halted>()
            }
        }

        "drain parks with no post-drain launches; resume finishes without double-execution" {
            runTest {
                val (emitter, _) = emitterWith()
                val mock = MockNodeExecutor()
                val executor = DagExecutor(emitter, InMemoryStepRepository(), mock)
                val plan = dag(listOf("A", "B", "C"), listOf("A" to "B", "B" to "C"))
                val completed = mutableSetOf<String>()

                // drain fires once the first node has run → the post-batch check parks.
                val parked = executor.execute(id, plan, completed, HandleTable(), drain = { mock.executed.size >= 1 })
                parked.shouldBeInstanceOf<ExecOutcome.Parked>()
                mock.executed shouldContainExactly listOf("A")
                completed shouldContainExactly setOf("A")

                // resume — no drain — finishes from the persisted frontier, no double-exec.
                val done = executor.execute(id, plan, completed, HandleTable(), drain = { false })
                done.shouldBeInstanceOf<ExecOutcome.Completed>()
                mock.executed shouldContainExactly listOf("A", "B", "C")
            }
        }

        "the execution event vocabulary is emitted (batch_* / step_*)" {
            runTest {
                val (emitter, events) = emitterWith()
                val executor = DagExecutor(emitter, InMemoryStepRepository(), MockNodeExecutor())
                executor.execute(id, dag(listOf("A")), mutableSetOf(), HandleTable())
                val kinds = events.replay(id, 0L).map { it.kind }
                kinds shouldContainExactly listOf("BATCH_LAUNCHED", "STEP_STARTED", "STEP_COMPLETED", "BATCH_COMPLETED")
            }
        }

        "a cyclic plan is rejected (HALTED)" {
            runTest {
                val (emitter, _) = emitterWith()
                val executor = DagExecutor(emitter, InMemoryStepRepository(), MockNodeExecutor())
                val plan = dag(listOf("A", "B"), listOf("A" to "B", "B" to "A"))
                executor.execute(id, plan, mutableSetOf(), HandleTable()).shouldBeInstanceOf<ExecOutcome.Halted>()
            }
        }
    })
