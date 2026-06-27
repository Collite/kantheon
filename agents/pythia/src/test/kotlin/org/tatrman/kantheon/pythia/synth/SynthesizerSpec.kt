package org.tatrman.kantheon.pythia.synth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.v1.StopReason
import java.util.UUID

/**
 * Stage 2.4 T2 — the synthesizer streams `synthesizer_block_*` events in order and
 * assembles a Conclusion with an honest `stop_reason` (never STOP_GOAL_REACHED on
 * a budget truncation).
 */
class SynthesizerSpec :
    StringSpec({

        fun harness(lead: String): Pair<Synthesizer, EventRepository> {
            val events = InMemoryEventRepository()
            val emitter = EventEmitter(events, RecordingNatsPublisher())
            return Synthesizer(ScriptedPromptExecutor(listOf(lead)), emitter) to events
        }

        val id = UUID.randomUUID()
        val tableBlock =
            Block
                .newBuilder()
                .setBlockId("t")
                .setRole(BlockRole.EVIDENCE)
                .build()

        "streams the lead + render blocks in order and reports a goal-reached conclusion" {
            runTest {
                val (synth, events) = harness("Found 23 customers.")
                val conclusion =
                    synth.synthesize(
                        id,
                        SynthContext(
                            locale = "en",
                            question = "q",
                            supportedStatements = listOf("data exists"),
                            renderBlocks = listOf(tableBlock),
                            stopReason = StopReason.STOP_GOAL_REACHED,
                            confidence = null,
                            evidenceStepIds = listOf("step-N1"),
                        ),
                    )
                conclusion.primary.blocksList
                    .first()
                    .text shouldBe "Found 23 customers."
                conclusion.primary.blocksCount shouldBe 2 // lead + table
                conclusion.stopReason shouldBe StopReason.STOP_GOAL_REACHED
                conclusion.budgetTruncated shouldBe false

                events.replay(id, 0L).map { it.kind } shouldContainInOrder
                    listOf("SYNTHESIZER_BLOCK_STARTED", "SYNTHESIZER_BLOCK_COMPLETED", "SYNTHESIZER_DONE")
            }
        }

        "a budget truncation is reported honestly (budget_truncated = true)" {
            runTest {
                val (synth, _) = harness("Partial result.")
                val conclusion =
                    synth.synthesize(
                        id,
                        SynthContext("en", "q", emptyList(), emptyList(), StopReason.STOP_BUDGET, null, emptyList()),
                    )
                conclusion.stopReason shouldBe StopReason.STOP_BUDGET
                conclusion.budgetTruncated shouldBe true
            }
        }
    })
