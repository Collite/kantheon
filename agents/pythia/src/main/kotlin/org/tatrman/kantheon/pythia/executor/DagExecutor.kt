package org.tatrman.kantheon.pythia.executor

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.api.ProtoJson
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.persistence.StepRepository
import org.tatrman.kantheon.pythia.persistence.StepRow
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.StepCost
import org.tatrman.kantheon.pythia.v1.StepError
import org.tatrman.kantheon.pythia.v1.StepRecord
import org.tatrman.kantheon.pythia.v1.StepStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Concurrency caps (architecture §5): per-investigation, per-provider, and global. */
data class ExecCaps(
    val perInvestigation: Int = 5,
    val perProvider: Int = 5,
    val global: Int = 8,
)

/** Terminal outcome of a (possibly partial) execution. */
sealed interface ExecOutcome {
    data object Completed : ExecOutcome

    data object Parked : ExecOutcome

    data class Halted(
        val reason: String,
    ) : ExecOutcome

    /** A downstream call rejected the bearer mid-run → park AWAITING_USER_INPUT (Stage 2.3). */
    data object NeedsReauth : ExecOutcome
}

/**
 * The custom-coroutine DAG executor (architecture §5). Round-based: each round
 * computes the frontier, launches the ready nodes (priority-ordered) under three
 * `Semaphore` caps, and awaits the batch. Tiered failure handling: transient →
 * retry (policy); permanent → block the node (its hypotheses INCONCLUSIVE, its
 * dependents pruned) + continue; systemic → HALT. Drain (entering any AWAITING_*)
 * stops new launches and returns [ExecOutcome.Parked]; an in-process resume re-runs
 * `execute` with the live `completed` set, so no node double-executes. (Across a
 * process restart the in-memory `completed`/handle state is gone, so a resumed
 * investigation re-runs its read-only query nodes from scratch — durable evidence
 * re-materialisation from checkpointed recipes is the Phase-4 PD-5 path.)
 */
class DagExecutor(
    private val emitter: EventEmitter,
    private val steps: StepRepository,
    private val nodeExecutor: NodeExecutor,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val caps: ExecCaps = ExecCaps(),
    private val priorityOf: (PlanNode) -> Double = { 0.0 },
    private val metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics? = null,
) {
    private val log = LoggerFactory.getLogger(DagExecutor::class.java)

    /** The set of hypotheses left INCONCLUSIVE by permanent step failures. */
    val inconclusiveHypotheses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pipeline warnings forwarded from node executors (Rule-6 channel; surfaced by the orchestrator). */
    val warnings: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    suspend fun execute(
        investigationId: UUID,
        plan: PlanDag,
        completed: MutableSet<String>,
        handles: HandleTable,
        bearer: String = "",
        locale: String = "en",
        sessionId: String = "",
        drain: () -> Boolean = { false },
    ): ExecOutcome {
        if (Topology.hasCycle(plan)) return ExecOutcome.Halted("plan graph contains a cycle")
        val global = Semaphore(caps.global)
        val perInvestigation = Semaphore(caps.perInvestigation)
        val perProvider = ConcurrentHashMap<String, Semaphore>()
        // Nodes whose upstream permanently failed: excluded from the frontier so their
        // dependents never run against a missing handle (and the failed node isn't retried).
        val blocked = mutableSetOf<String>()
        var batchNo = 0

        while (true) {
            if (drain()) return ExecOutcome.Parked
            val ready = Topology.frontier(plan, completed, blocked)
            if (ready.isEmpty()) break
            val batch = ready.sortedByDescending(priorityOf)
            val batchId = "b${++batchNo}"
            metrics?.batchParallelism(batch.size)
            emitter.emit(
                investigationId,
                Events.batchLaunched(batchId, batch.map { it.nodeId }, batch.size * 0.05, caps.perInvestigation),
            )

            val outcomes =
                coroutineScope {
                    batch
                        .map { node ->
                            async {
                                if (drain()) {
                                    NodeOutcome.Skipped
                                } else {
                                    runNode(
                                        investigationId,
                                        node,
                                        NodeContext(handles, bearer, locale, sessionId),
                                        global,
                                        perInvestigation,
                                        perProvider,
                                    )
                                }
                            }
                        }.awaitAll()
                }

            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val inconclusive = mutableListOf<String>()
            var halt: String? = null
            var needsReauth = false
            var actualCost = 0.0
            batch.zip(outcomes).forEach { (node, outcome) ->
                when (outcome) {
                    is NodeOutcome.Success -> {
                        completed += node.nodeId
                        succeeded += node.nodeId
                        actualCost += outcome.costUsd
                        warnings += outcome.warnings
                    }
                    is NodeOutcome.Permanent -> {
                        // Block (don't complete) the node: its hypotheses are INCONCLUSIVE and
                        // its dependents must not run against a handle that was never produced.
                        blocked += node.nodeId
                        failed += node.nodeId
                        inconclusive += node.testsHypIdsList
                        inconclusiveHypotheses += node.testsHypIdsList
                    }
                    is NodeOutcome.Systemic -> halt = outcome.reason
                    NodeOutcome.NeedsReauth -> needsReauth = true
                    NodeOutcome.Skipped -> {}
                }
            }
            emitter.emit(investigationId, Events.batchCompleted(batchId, succeeded, actualCost))
            log.debug("batch {} done: ok={} failed={} inconclusive={}", batchId, succeeded, failed, inconclusive)
            if (needsReauth) return ExecOutcome.NeedsReauth
            halt?.let { return ExecOutcome.Halted(it) }
            if (drain()) return ExecOutcome.Parked
        }
        return ExecOutcome.Completed
    }

    private sealed interface NodeOutcome {
        data class Success(
            val costUsd: Double,
            val warnings: List<String>,
        ) : NodeOutcome

        data object Permanent : NodeOutcome

        data class Systemic(
            val reason: String,
        ) : NodeOutcome

        data object NeedsReauth : NodeOutcome

        data object Skipped : NodeOutcome
    }

    private suspend fun runNode(
        investigationId: UUID,
        node: PlanNode,
        ctx: NodeContext,
        global: Semaphore,
        perInvestigation: Semaphore,
        perProvider: ConcurrentHashMap<String, Semaphore>,
    ): NodeOutcome {
        val provider = nodeExecutor.providerOf(node)
        val providerSem = perProvider.computeIfAbsent(provider) { Semaphore(caps.perProvider) }
        val stepId = "step-${node.nodeId}"
        return global.withPermit {
            perInvestigation.withPermit {
                providerSem.withPermit {
                    emitter.emit(investigationId, Events.stepStarted(stepId, node.kindCase.name, node.nodeId))
                    var attempts = 0
                    try {
                        val result =
                            retryPolicy.withRetry(
                                onRetry = { attempt, reason ->
                                    attempts = attempt
                                    emitter.emit(investigationId, Events.stepRetrying(stepId, attempt, reason))
                                },
                            ) {
                                nodeExecutor.execute(node, ctx)
                            }
                        persistStep(
                            investigationId,
                            stepId,
                            node,
                            StepStatus.STEP_COMPLETED,
                            attempts + 1,
                            result.costUsd,
                            null,
                        )
                        emitter.emit(
                            investigationId,
                            Events.stepCompleted(stepId, result.outputHandle?.handleId ?: "", result.rowCount),
                        )
                        metrics?.step(node.kindCase.name, "completed")
                        // LLM-bearing nodes (reasoning/render narrative) carry a non-zero cost; SQL/
                        // model/dataframe nodes are 0.0. task_kind = node kind, tier ≈ provider.
                        if (result.costUsd > 0.0) {
                            metrics?.llmCall(provider, node.kindCase.name.lowercase(), result.costUsd)
                        }
                        NodeOutcome.Success(result.costUsd, result.warnings)
                    } catch (e: TokenExpiredException) {
                        log.info("step {} hit token expiry — parking for re-auth", stepId)
                        NodeOutcome.NeedsReauth
                    } catch (e: NodeExecutionException) {
                        persistStep(investigationId, stepId, node, StepStatus.STEP_FAILED, attempts + 1, 0.0, e)
                        metrics?.step(node.kindCase.name, "failed")
                        emitter.emit(
                            investigationId,
                            Events.stepFailed(stepId, e.kind.name, e.message ?: "", e.kind == FailureKind.TRANSIENT),
                        )
                        if (e.kind ==
                            FailureKind.SYSTEMIC
                        ) {
                            NodeOutcome.Systemic(e.message ?: "systemic failure")
                        } else {
                            NodeOutcome.Permanent
                        }
                    }
                }
            }
        }
    }

    private fun persistStep(
        investigationId: UUID,
        stepId: String,
        node: PlanNode,
        status: StepStatus,
        attempts: Int,
        costUsd: Double,
        error: NodeExecutionException?,
    ) {
        val record =
            StepRecord
                .newBuilder()
                .setId(stepId)
                .setNodeId(node.nodeId)
                .setStatus(status)
                .setAttempts(attempts)
                .setCost(StepCost.newBuilder().setUsd(costUsd))
                .apply {
                    error?.let {
                        setError(
                            StepError
                                .newBuilder()
                                .setCode(it.kind.name)
                                .setMessage(it.message ?: "")
                                .setRecoverable(it.kind == FailureKind.TRANSIENT),
                        )
                    }
                }.build()
        steps.upsert(
            StepRow(
                investigationId = investigationId,
                stepId = stepId,
                nodeId = node.nodeId,
                bodyJson = ProtoJson.print(record),
                status = status.name,
            ),
        )
    }
}
