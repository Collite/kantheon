package org.tatrman.kantheon.pythia.orchestrator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.pythia.api.ProtoJson
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.evaluate.EvaluatedHypothesis
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.executor.ExecOutcome
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.HypothesisRecord
import org.tatrman.kantheon.pythia.persistence.HypothesisRepository
import org.tatrman.kantheon.pythia.revise.ExplainedVariance
import org.tatrman.kantheon.pythia.revise.ReviseResult
import org.tatrman.kantheon.pythia.revise.StopConditions
import org.tatrman.kantheon.pythia.revise.StopDecision
import org.tatrman.kantheon.pythia.revise.StopState
import org.tatrman.kantheon.pythia.suspicion.Expectation
import org.tatrman.kantheon.pythia.suspicion.SuspicionAction
import org.tatrman.kantheon.pythia.synth.SynthContext
import org.tatrman.kantheon.pythia.persistence.CheckpointReason
import org.tatrman.kantheon.pythia.persistence.InvestigationRecord
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.persistence.SchedulerState
import org.tatrman.kantheon.pythia.plan.PlanResult
import org.tatrman.kantheon.pythia.plan.Planner
import org.tatrman.kantheon.pythia.resolve.HandoffAnchor
import org.tatrman.kantheon.pythia.resolve.ResolveOutcome
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.resolve.ThemisAuthException
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.Conclusion
import org.tatrman.kantheon.pythia.v1.ConfidenceInfo
import org.tatrman.kantheon.pythia.v1.ConfidenceKind
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.RenderableArtifact
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResourceUsage
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.StopReason
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Resume outcome for the control endpoints (maps to 200 / 404 / 409). */
sealed interface ResumeOutcome {
    data class Ok(
        val newStatus: Status,
    ) : ResumeOutcome

    data class Conflict(
        val currentStatus: Status,
    ) : ResumeOutcome

    data object NotFound : ResumeOutcome
}

/** What `/budget-decision` carries (contracts §2). */
enum class BudgetDecision { CONTINUE, HALT_GRACEFULLY, ABANDON }

/**
 * Drives the 12-status lifecycle. Re-entrant: [advanceLoop] reads the persisted
 * status and runs the matching stage until it parks (AWAITING_*) or terminates;
 * resume endpoints set the next status (idempotent via `tryResume`) and re-launch
 * the loop. Entering any AWAITING_* checkpoints, emits `scheduler_drained`, and
 * records `awaiting_since` / `awaiting_ttl_until`.
 *
 * [resolver] and [planner] are wired from Phase 2 Stage 2.1; when null the stage
 * runs a scripted stub (Phase 1 behaviour). Execution + synthesis remain stubs
 * until Stages 2.2–2.4.
 */
class InvestigationOrchestrator(
    private val investigations: InvestigationRepository,
    private val checkpointer: Checkpointer,
    private val emitter: EventEmitter,
    private val scope: CoroutineScope,
    private val awaitingTtlHours: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val resolver: Resolver? = null,
    private val planner: Planner? = null,
    private val capabilityCatalog: suspend () -> List<String> = { emptyList() },
    private val executionEngine: ExecutionEngine? = null,
    private val hypothesesRepo: HypothesisRepository? = null,
    private val metrics: org.tatrman.kantheon.pythia.obs.PythiaMetrics? = null,
) {
    private val log = LoggerFactory.getLogger(InvestigationOrchestrator::class.java)
    private val terminals = ConcurrentHashMap<UUID, CompletableDeferred<Status>>()
    private val bearers = ConcurrentHashMap<UUID, String>()
    private val execStates = ConcurrentHashMap<UUID, ExecState>()

    /**
     * Investigations a `halt` has been requested for. Read by the executor's drain
     * predicate so a running batch stops launching, and consulted by the EXECUTING
     * driver so a drained execution heads to partial synthesis instead of parking.
     */
    private val haltRequested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Investigations whose resolution is frozen from a parent (reproduce) — skip the resolver. */
    private val frozenResolutions: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Per-investigation execution state (handles + completed nodes + hypothesis verdicts). */
    private class ExecState {
        val handles = HandleTable()
        val completed: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()
        var verdicts: List<EvaluatedHypothesis> = emptyList()

        /** Set once the user has been consulted on a suspicious result — don't re-halt on resume. */
        @Volatile
        var suspicionAcked: Boolean = false
    }

    /** Persist the submitted investigation and launch its driver. Returns the id. */
    fun submit(
        investigation: Investigation,
        bearer: String = "",
    ): UUID = submitInternal(investigation, bearer, frozenResolutionJson = null)

    /**
     * `replay` (design §3.6, contracts §2): a new investigation that **re-resolves**
     * the parent's question (relative params re-resolve against today), linked via
     * `parent_id`. Returns the new id, or null if the parent is unknown.
     */
    fun replay(
        parentId: UUID,
        overridesJson: String?,
        bearer: String = "",
    ): UUID? {
        val parent = investigations.findById(parentId) ?: return null
        val parentInv = parseRequest(parent)
        val derived =
            parentInv
                .toBuilder()
                .clearId()
                .setParentId(parentId.toString())
                .build()
        // Run under the *caller's* live bearer (PD-8) — never the parent's captured/expired token.
        return submitInternal(derived, bearer, frozenResolutionJson = null)
    }

    /**
     * `reproduce` (design §3.6): a new investigation with the parent's **frozen
     * resolved params** — skip re-resolution, reuse the parent's resolution. Phase-4
     * blob reuse is noted via Rule-6 (PgResultSnapshots are re-materialised here).
     */
    fun reproduce(
        parentId: UUID,
        bearer: String = "",
    ): UUID? {
        val parent = investigations.findById(parentId) ?: return null
        val parentInv = parseRequest(parent)
        val derived =
            parentInv
                .toBuilder()
                .clearId()
                .setParentId(parentId.toString())
                .build()
        // Caller's live bearer (PD-8); the parent's *resolution* is frozen, but never its identity.
        return submitInternal(derived, bearer, frozenResolutionJson = parent.resolutionJson)
    }

    private fun submitInternal(
        investigation: Investigation,
        bearer: String,
        frozenResolutionJson: String?,
    ): UUID {
        val id = investigation.id.takeIf { it.isNotBlank() }?.let(UUID::fromString) ?: UUID.randomUUID()
        val userId = investigation.caller.userId
        val now = clock.instant()
        val stamped = investigation.toBuilder().setId(id.toString()).build()
        val warnings =
            if (frozenResolutionJson !=
                null
            ) {
                """["reproduce: re-materialising from frozen resolved params (blob reuse is Phase 4)"]"""
            } else {
                "[]"
            }
        investigations.insert(
            InvestigationRecord(
                id = id,
                parentId = stamped.parentId.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                callerJson = ProtoJson.print(investigation.caller),
                question = investigation.question,
                requestJson = ProtoJson.print(stamped),
                status = Status.STATUS_SUBMITTED.name,
                resolutionJson = frozenResolutionJson,
                warningsJson = warnings,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (frozenResolutionJson != null) frozenResolutions += id
        bearers[id] = bearer
        terminals[id] = CompletableDeferred()
        emitter.emit(id, Events.submitted(stamped))
        scope.launch { runCatching { advanceLoop(id, userId) }.onFailure { fail(id, userId, it) } }
        return id
    }

    /** Suspend until [id] reaches a terminal status (test/await hook). */
    suspend fun awaitTerminal(id: UUID): Status = terminals.getOrPut(id) { CompletableDeferred() }.await()

    private suspend fun advanceLoop(
        id: UUID,
        userId: String,
    ) {
        while (true) {
            val rec = investigations.findById(id) ?: return
            when (Status.valueOf(rec.status)) {
                Status.STATUS_SUBMITTED ->
                    if (!forward(id, userId, Status.STATUS_SUBMITTED, Status.STATUS_RESOLVING)) return
                Status.STATUS_RESOLVING -> {
                    emitter.emit(id, Events.resolutionStarted(rec.question))
                    when (val r = doResolve(id, rec)) {
                        ResolveStep.Proceed ->
                            if (!forward(id, userId, Status.STATUS_RESOLVING, Status.STATUS_PLANNING)) return
                        ResolveStep.Reauth -> {
                            park(id, userId, Status.STATUS_RESOLVING, Status.STATUS_AWAITING_RESOLUTION_INPUT)
                            return
                        }
                        ResolveStep.Clarify -> {
                            park(id, userId, Status.STATUS_RESOLVING, Status.STATUS_AWAITING_RESOLUTION_INPUT)
                            return
                        }
                        is ResolveStep.Fail -> {
                            failTerminal(id, userId, Status.STATUS_FAILED, r.gaps)
                            return
                        }
                    }
                }
                Status.STATUS_PLANNING -> {
                    when (val p = doPlan(id, rec, userId)) {
                        PlanStep.Drafted -> {
                            if (requiresPlanApproval(rec)) {
                                park(id, userId, Status.STATUS_PLANNING, Status.STATUS_AWAITING_PLAN_APPROVAL)
                                return
                            }
                            if (!forward(id, userId, Status.STATUS_PLANNING, Status.STATUS_EXECUTING)) return
                        }
                        is PlanStep.Halt -> {
                            failTerminal(id, userId, Status.STATUS_FAILED, listOf(p.reason))
                            return
                        }
                    }
                }
                Status.STATUS_EXECUTING -> {
                    when (val e = doExecute(id, userId, rec)) {
                        ExecuteStep.Proceed ->
                            if (!forward(id, userId, Status.STATUS_EXECUTING, Status.STATUS_SYNTHESIZING)) return
                        ExecuteStep.AwaitInput -> {
                            park(id, userId, Status.STATUS_EXECUTING, Status.STATUS_AWAITING_USER_INPUT)
                            return
                        }
                        ExecuteStep.AwaitRevisionApproval -> {
                            park(id, userId, Status.STATUS_EXECUTING, Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL)
                            return
                        }
                        ExecuteStep.HaltRequested -> {
                            haltSynthesize(id, userId, rec)
                            return
                        }
                        is ExecuteStep.Inconclusive -> {
                            failTerminal(id, userId, Status.STATUS_INCONCLUSIVE, listOf(e.reason))
                            return
                        }
                    }
                }
                Status.STATUS_SYNTHESIZING -> {
                    doSynthesize(id, rec, partial = false)
                    finishTerminal(id, userId, Status.STATUS_SYNTHESIZING, Status.STATUS_DONE)
                    return
                }
                else -> return // parked (AWAITING_*) or terminal
            }
        }
    }

    // ---- resolve / plan stages (real subsystems when wired; else stubs) ----

    private sealed interface ResolveStep {
        data object Proceed : ResolveStep

        data object Clarify : ResolveStep

        /** Themis rejected the bearer mid-run (token expiry) — park for re-auth, don't fail. */
        data object Reauth : ResolveStep

        data class Fail(
            val gaps: List<String>,
        ) : ResolveStep
    }

    private suspend fun doResolve(
        id: UUID,
        rec: InvestigationRecord,
    ): ResolveStep {
        // reproduce: the resolution is frozen from the parent — skip the resolver entirely.
        if (id in frozenResolutions) return ResolveStep.Proceed
        val r = resolver ?: return ResolveStep.Proceed
        val investigation = parseRequest(rec)
        return try {
            when (val outcome = r.resolve(investigation, bearers[id] ?: "")) {
                is ResolveOutcome.Resolved -> {
                    saveResolution(id, outcome.resolution)
                    ResolveStep.Proceed
                }
                is ResolveOutcome.Clarify ->
                    ResolveStep.Clarify
                is ResolveOutcome.Refuse ->
                    ResolveStep.Fail(outcome.gaps)
            }
        } catch (e: ThemisAuthException) {
            // Fail-closed-then-resume (kantheon-security §2.1): a mid-run bearer rejection
            // parks AWAITING_RESOLUTION_INPUT to resume under a fresh token — never a dead FAILED.
            log.info("themis rejected the bearer for {} — parking for re-auth: {}", id, e.message)
            ResolveStep.Reauth
        }
    }

    private sealed interface PlanStep {
        data object Drafted : PlanStep

        data class Halt(
            val reason: String,
        ) : PlanStep
    }

    private suspend fun doPlan(
        id: UUID,
        rec: InvestigationRecord,
        userId: String,
    ): PlanStep {
        val p = planner
        if (p == null) {
            planStub(id, rec)
            return PlanStep.Drafted
        }
        val investigation = parseRequest(rec)
        val resolution = parseResolution(rec)
        val anchor = resolver?.seedAnchor(investigation) ?: EMPTY_ANCHOR
        return when (
            val result =
                p.plan(
                    resolution,
                    anchor,
                    investigation.constraints,
                    investigation.context.locale,
                    capabilityCatalog(),
                )
        ) {
            is PlanResult.Drafted -> {
                savePlan(id, result.plan)
                emitter.emit(id, Events.planDrafted(result.plan, requiresPlanApproval(rec)))
                PlanStep.Drafted
            }
            is PlanResult.Halt -> PlanStep.Halt(result.reason)
        }
    }

    private sealed interface ExecuteStep {
        data object Proceed : ExecuteStep

        /** Park AWAITING_USER_INPUT (re-auth or a HALT-policy suspicious result). */
        data object AwaitInput : ExecuteStep

        /** Park AWAITING_PLAN_REVISION_APPROVAL (on_plan_revision = APPROVE). */
        data object AwaitRevisionApproval : ExecuteStep

        /** A `/halt` arrived mid-execution; drain → partial synthesis → HALTED. */
        data object HaltRequested : ExecuteStep

        data class Inconclusive(
            val reason: String,
        ) : ExecuteStep
    }

    /**
     * Execute → evaluate → (deepen) loop (Stage 3.3). Runs the current plan, checks
     * suspicion, evaluates hypotheses, emits prioritisation/deepening, then consults
     * the stop spine: if the plan is exhausted (or deepening is warranted) and the
     * reviser is wired + under the revision cap, it revises and re-executes; APPROVE
     * policy parks AWAITING_PLAN_REVISION_APPROVAL. Bounded by the depth-budget cap.
     */
    private suspend fun doExecute(
        id: UUID,
        userId: String,
        rec: InvestigationRecord,
    ): ExecuteStep {
        val engine = executionEngine
        if (engine == null) {
            executeStub(id)
            return ExecuteStep.Proceed
        }
        val request = parseRequest(rec)
        val state = execStates.getOrPut(id) { ExecState() }
        val intent = parseResolution(rec).resolvedIntent.kind
        var plan = parsePlan(rec)
        var guard = 0
        while (guard++ < MAX_DEEPENING_ROUNDS) {
            when (
                val outcome =
                    engine.dagExecutor.execute(
                        id,
                        plan,
                        state.completed,
                        state.handles,
                        bearers[id] ?: "",
                        request.context.locale,
                        sessionId = id.toString(),
                        drain = { id in haltRequested },
                    )
            ) {
                ExecOutcome.Completed -> {}
                ExecOutcome.NeedsReauth -> return ExecuteStep.AwaitInput
                ExecOutcome.Parked ->
                    // Drained: a halt was requested → head to partial synthesis, not a park.
                    return if (id in haltRequested) ExecuteStep.HaltRequested else ExecuteStep.AwaitInput
                is ExecOutcome.Halted -> return ExecuteStep.Inconclusive(outcome.reason)
            }
            if (runSuspicion(id, plan, state, request.hitlPolicy.onSuspiciousResult) == SuspicionAction.HALT) {
                return ExecuteStep.AwaitInput
            }
            evaluateHypotheses(id, plan, state)
            emitPrioritisation(id, plan, state)

            val reviser = engine.reviser ?: return ExecuteStep.Proceed
            if (goalOrTerminal(intent, request.constraints.depthBudget, plan, state)) return ExecuteStep.Proceed

            // Deepen: revise the plan and re-execute, unless capped/halted/approval-gated.
            when (
                val rr =
                    reviser.revise(
                        id,
                        plan,
                        trigger = deepeningTrigger(state),
                        depthBudget = request.constraints.depthBudget,
                        locale = request.context.locale,
                        constraints = request.constraints,
                        onPlanRevision = request.hitlPolicy.onPlanRevision,
                    )
            ) {
                is ReviseResult.Revised -> {
                    savePlan(id, rr.plan)
                    metrics?.planRevision(deepeningTrigger(state))
                    plan = rr.plan
                }
                is ReviseResult.NeedsApproval -> {
                    savePlan(id, rr.plan)
                    checkpointer.checkpoint(
                        id,
                        CheckpointReason.PLAN_REVISED,
                        SchedulerState(revision = rr.plan.revision),
                    )
                    return ExecuteStep.AwaitRevisionApproval
                }
                is ReviseResult.Halt, ReviseResult.CapReached -> return ExecuteStep.Proceed
            }
        }
        return ExecuteStep.Proceed
    }

    /** Whether a terminal stop reason (user/budget/hard-cap/goal) was reached — proceed to synth. */
    private fun goalOrTerminal(
        intent: org.tatrman.kantheon.pythia.v1.IntentKind,
        depthBudget: org.tatrman.kantheon.pythia.v1.DepthBudget,
        plan: PlanDag,
        state: ExecState,
    ): Boolean {
        val supportedHyps =
            plan.hypothesesList.filter { h ->
                state.verdicts.any {
                    it.hypId == h.id &&
                        it.status == HypStatus.HYP_SUPPORTED
                }
            }
        val decision =
            StopConditions.decide(
                intent,
                StopState(
                    explainedVariance = ExplainedVariance.compute(supportedHyps),
                    revisionCount = plan.revision,
                    maxRevisions = reviserCap(depthBudget),
                    frontierEmpty = state.completed.containsAll(plan.nodesList.map { it.nodeId }),
                    goalReached =
                        supportedHyps.isNotEmpty() && intent != org.tatrman.kantheon.pythia.v1.IntentKind.INTENT_RCA,
                ),
            )
        // A goal/user/budget/hard-cap stop ends the loop; plan-exhausted instead triggers a deepening attempt.
        return decision is StopDecision.Stop && decision.reason != StopReason.STOP_PLAN_EXHAUSTED
    }

    private fun deepeningTrigger(state: ExecState): String =
        "verdicts: " + state.verdicts.joinToString(", ") { "${it.hypId}=${it.status.name}" }

    private fun emitPrioritisation(
        id: UUID,
        plan: PlanDag,
        state: ExecState,
    ) {
        val ranked =
            org.tatrman.kantheon.pythia.revise.Prioritisation
                .prioritize(plan.hypothesesList)
        if (ranked.isEmpty()) return
        emitter.emit(
            id,
            Events.hypothesesPrioritized(
                ranked.map {
                    org.tatrman.kantheon.pythia.v1.PrioritizedHyp
                        .newBuilder()
                        .setHypId(it.hypId)
                        .setScore(it.score)
                        .setRationale(it.rationale)
                        .build()
                },
            ),
        )
        val tieBreak =
            org.tatrman.kantheon.pythia.revise.Prioritisation
                .topTwoWithinTenPercent(ranked)
        emitter.emit(
            id,
            Events.deepeningDecision(
                choseHypId = ranked.first().hypId,
                score = ranked.first().score,
                rationale = "highest-scored hypothesis",
                alternates = ranked.drop(1).take(2).map { it.hypId },
                tieBreakUsed = tieBreak,
            ),
        )
    }

    private fun reviserCap(depth: org.tatrman.kantheon.pythia.v1.DepthBudget): Int =
        when (depth) {
            org.tatrman.kantheon.pythia.v1.DepthBudget.DEPTH_SHALLOW -> 0
            org.tatrman.kantheon.pythia.v1.DepthBudget.DEPTH_DEEP -> 4
            else -> 2
        }

    /** Classify each query result + apply the on_suspicious_result policy; returns the worst action. */
    private fun runSuspicion(
        id: UUID,
        plan: PlanDag,
        state: ExecState,
        policy: org.tatrman.kantheon.pythia.v1.SuspicionPolicy,
    ): SuspicionAction {
        val engine = executionEngine ?: return SuspicionAction.CONTINUE
        val classifier = engine.suspicionClassifier ?: return SuspicionAction.CONTINUE
        val handler = engine.suspicionPolicy ?: return SuspicionAction.CONTINUE
        if (state.suspicionAcked) return SuspicionAction.CONTINUE // user already consulted on resume
        var worst = SuspicionAction.CONTINUE
        val warnings = mutableListOf<String>()
        for (node in plan.nodesList.filter { it.hasQuery() }) {
            val rows = state.handles.rows("h-${node.nodeId}") ?: continue
            val cols = (rows.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.keys?.toList() ?: emptyList()
            val verdict = classifier.classify(rows, cols, engine.dagExecutor.warnings, Expectation(expectRows = true))
            when (handler.apply(id, "step-${node.nodeId}", verdict, policy)) {
                SuspicionAction.WARN -> warnings += verdict.reasons
                SuspicionAction.HALT -> worst = SuspicionAction.HALT
                SuspicionAction.CONTINUE -> {}
            }
        }
        if (warnings.isNotEmpty()) {
            investigations.findById(id)?.let {
                investigations.save(it.copy(warningsJson = appendWarnings(it.warningsJson, warnings)))
            }
        }
        return worst
    }

    /** Map node→hypothesis tests, evaluate the rules-first verdicts, persist them. */
    private suspend fun evaluateHypotheses(
        id: UUID,
        plan: PlanDag,
        state: ExecState,
    ) {
        val engine = executionEngine ?: return
        val rowsByStep = mutableMapOf<String, JsonArray>()
        val hypToSteps = mutableMapOf<String, MutableList<String>>()
        for (node in plan.nodesList) {
            val stepId = "step-${node.nodeId}"
            state.handles.rows("h-${node.nodeId}")?.let { rowsByStep[stepId] = it }
            node.testsHypIdsList.forEach { hypToSteps.getOrPut(it) { mutableListOf() }.add(stepId) }
        }
        val hyps =
            plan.hypothesesList.map {
                it
                    .toBuilder()
                    .clearTestStepIds()
                    .addAllTestStepIds(hypToSteps[it.id] ?: emptyList())
                    .build()
            }
        state.verdicts = engine.evaluator.evaluate(id, hyps, rowsByStep)
        persistVerdicts(id, hyps, state.verdicts)
    }

    private fun persistVerdicts(
        id: UUID,
        hyps: List<Hypothesis>,
        verdicts: List<EvaluatedHypothesis>,
    ) {
        val repo = hypothesesRepo ?: return
        val byId = hyps.associateBy { it.id }
        verdicts.forEach { v ->
            val hyp =
                (byId[v.hypId] ?: Hypothesis.newBuilder().setId(v.hypId).build())
                    .toBuilder()
                    .setStatus(v.status)
                    .setConfidence(v.confidence)
                    .build()
            repo.upsert(
                HypothesisRecord(
                    investigationId = id,
                    hypId = v.hypId,
                    parentHypId = hyp.parentId.ifBlank { null },
                    bodyJson = ProtoJson.print(hyp),
                    status = v.status.name,
                    confidence = v.confidence,
                ),
            )
        }
    }

    private suspend fun doSynthesize(
        id: UUID,
        rec: InvestigationRecord,
        partial: Boolean,
    ) {
        val engine = executionEngine
        if (engine == null) {
            synthesizeStub(id, partial)
            return
        }
        val plan = parsePlan(rec)
        val state = execStates[id] ?: ExecState()
        val request = parseRequest(rec)
        val supportedHyps =
            plan.hypothesesList.filter { h ->
                state.verdicts.any {
                    it.hypId == h.id &&
                        it.status == HypStatus.HYP_SUPPORTED
                }
            }
        val intent = parseResolution(rec).resolvedIntent.kind
        // Honest stop reason via the same spine the deepening loop consults — so a SHALLOW
        // run that simply exhausted its (zero-revision) plan reports STOP_PLAN_EXHAUSTED, not
        // a spurious STOP_HARD_CAP, and a halt is STOP_USER. budgetTruncated keys off the
        // genuine-truncation reasons only (Synthesizer).
        val decision =
            StopConditions.decide(
                intent,
                StopState(
                    userHalt = partial,
                    explainedVariance = ExplainedVariance.compute(supportedHyps),
                    revisionCount = plan.revision,
                    maxRevisions = reviserCap(request.constraints.depthBudget),
                    frontierEmpty = state.completed.containsAll(plan.nodesList.map { it.nodeId }),
                    goalReached =
                        supportedHyps.isNotEmpty() &&
                            intent != org.tatrman.kantheon.pythia.v1.IntentKind.INTENT_RCA,
                ),
            )
        val stopReason = (decision as? StopDecision.Stop)?.reason ?: StopReason.STOP_GOAL_REACHED
        val ctx =
            SynthContext(
                locale = request.context.locale,
                question = rec.question,
                supportedStatements = supportedHyps.map { it.statement },
                renderBlocks = state.handles.blocks(),
                stopReason = stopReason,
                // Heuristic explained-variance caveat (Stage 3.3 T2); null for procedural (no explanatory signal).
                confidence = ExplainedVariance.confidence(supportedHyps),
                evidenceStepIds = plan.nodesList.map { "step-${it.nodeId}" },
                sourceTurnRef = request.context.handoff.sourceTurnRef,
            )
        val conclusion = engine.synthesizer.synthesize(id, ctx)
        emitter.emit(id, Events.conclusion(conclusion))
        investigations.findById(id)?.let { investigations.save(it.copy(conclusionJson = ProtoJson.print(conclusion))) }
        finaliseEvidence(id, engine, plan, state, supportedHyps, request.constraints.depthBudget)
        execStates.remove(id)
    }

    /**
     * Evidence persistence + GC at finalisation (Stage 4.1 T4). Handles backing a
     * supported hypothesis are load-bearing → persisted to `pythia-evidence`; the rest
     * are transient → evicted. Best-effort: a Charon failure becomes a Rule-6 warning,
     * never a failed synthesis. No-op when no evidence manager is wired (SQL-only / tests).
     */
    private suspend fun finaliseEvidence(
        id: UUID,
        engine: ExecutionEngine,
        plan: PlanDag,
        state: ExecState,
        supportedHyps: List<Hypothesis>,
        depth: DepthBudget,
    ) {
        val mgr = engine.evidenceManager ?: return
        val supportedIds = supportedHyps.map { it.id }.toSet()
        val loadBearingHandleIds =
            plan.nodesList
                .filter { node -> node.testsHypIdsList.any { it in supportedIds } }
                .map { "h-${it.nodeId}" }
                .toSet()
        val allHandleIds = plan.nodesList.map { "h-${it.nodeId}" }
        runCatching {
            val result = mgr.finalise(id.toString(), state.handles, loadBearingHandleIds, depth, allHandleIds)
            if (result.warnings.isNotEmpty()) {
                investigations.findById(id)?.let {
                    investigations.save(it.copy(warningsJson = appendWarnings(it.warningsJson, result.warnings)))
                }
            }
        }.onFailure { log.warn("evidence finalisation failed for {}: {}", id, it.message) }
    }

    // ---- resume endpoints (idempotent) ----

    fun approvePlan(
        id: UUID,
        approve: Boolean,
    ): ResumeOutcome =
        resumeFrom(id, Status.STATUS_AWAITING_PLAN_APPROVAL) { userId ->
            val to = if (approve) Status.STATUS_EXECUTING else Status.STATUS_PLANNING
            resumeTo(id, userId, Status.STATUS_AWAITING_PLAN_APPROVAL, to)
        }

    fun approveRevision(
        id: UUID,
        @Suppress("UNUSED_PARAMETER") approve: Boolean,
    ): ResumeOutcome =
        resumeFrom(id, Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL) { userId ->
            resumeTo(id, userId, Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL, Status.STATUS_EXECUTING)
        }

    fun answer(id: UUID): ResumeOutcome {
        val rec = investigations.findById(id) ?: return ResumeOutcome.NotFound
        val cur = Status.valueOf(rec.status)
        val to =
            when (cur) {
                Status.STATUS_AWAITING_RESOLUTION_INPUT -> Status.STATUS_RESOLVING
                Status.STATUS_AWAITING_USER_INPUT -> Status.STATUS_EXECUTING
                else -> return ResumeOutcome.Conflict(cur)
            }
        // The user has been consulted (re-auth or a suspicious result) — proceed without re-halting.
        if (cur == Status.STATUS_AWAITING_USER_INPUT) execStates[id]?.suspicionAcked = true
        return resumeTo(id, userIdOf(rec), cur, to)
    }

    fun budgetDecision(
        id: UUID,
        decision: BudgetDecision,
    ): ResumeOutcome =
        resumeFrom(id, Status.STATUS_AWAITING_BUDGET_DECISION) { userId ->
            val to =
                when (decision) {
                    BudgetDecision.CONTINUE -> Status.STATUS_EXECUTING
                    BudgetDecision.HALT_GRACEFULLY -> Status.STATUS_SYNTHESIZING
                    BudgetDecision.ABANDON -> Status.STATUS_HALTED
                }
            // A budget-ladder halt: count the ones that stop the run (CONTINUE resumes, so it's not a halt).
            if (decision != BudgetDecision.CONTINUE) metrics?.budgetHalt(decision.name)
            resumeTo(id, userId, Status.STATUS_AWAITING_BUDGET_DECISION, to)
        }

    fun halt(id: UUID): ResumeOutcome {
        val rec = investigations.findById(id) ?: return ResumeOutcome.NotFound
        val cur = Status.valueOf(rec.status)
        val userId = userIdOf(rec)
        if (TransitionTable.isTerminal(cur)) return ResumeOutcome.Conflict(cur)
        // Signal the executor to drain (it stops launching and the EXECUTING driver
        // heads to partial synthesis instead of parking).
        haltRequested += id
        scope.launch {
            runCatching {
                if (cur == Status.STATUS_EXECUTING) {
                    // Race the driver for the single EXECUTING→SYNTHESIZING claim (CAS arbitrates).
                    haltSynthesize(id, userId, rec)
                } else if (TransitionTable.isLegal(cur, Status.STATUS_HALTED)) {
                    finishTerminal(id, userId, cur, Status.STATUS_HALTED)
                }
            }.onFailure { fail(id, userId, it) }
        }
        return ResumeOutcome.Ok(Status.STATUS_HALTED)
    }

    /**
     * Drive a halt to its terminal: claim EXECUTING→SYNTHESIZING (idempotent CAS — only
     * the winner proceeds), synthesise partial findings, then finalise HALTED. Shared by
     * the `/halt` coroutine and the EXECUTING driver when it observes the drain signal, so
     * exactly one of them synthesises regardless of who got there first.
     */
    private suspend fun haltSynthesize(
        id: UUID,
        userId: String,
        rec: InvestigationRecord,
    ) {
        if (forward(id, userId, Status.STATUS_EXECUTING, Status.STATUS_SYNTHESIZING)) {
            doSynthesize(id, rec, partial = true)
            finishTerminal(id, userId, Status.STATUS_SYNTHESIZING, Status.STATUS_HALTED)
        }
    }

    private inline fun resumeFrom(
        id: UUID,
        expected: Status,
        block: (userId: String) -> ResumeOutcome,
    ): ResumeOutcome {
        val rec = investigations.findById(id) ?: return ResumeOutcome.NotFound
        val cur = Status.valueOf(rec.status)
        if (cur != expected) return ResumeOutcome.Conflict(cur)
        return block(userIdOf(rec))
    }

    private fun resumeTo(
        id: UUID,
        userId: String,
        from: Status,
        to: Status,
    ): ResumeOutcome {
        // Stamp how long we sat in the AWAITING_* state, read before the resume clears it.
        val awaitingSince = if (from in TransitionTable.AWAITING) investigations.findById(id)?.awaitingSince else null
        if (!checkpointer.tryResume(id, from.name, to.name)) {
            val now = investigations.findById(id)?.let { Status.valueOf(it.status) } ?: Status.STATUS_UNSPECIFIED
            return ResumeOutcome.Conflict(now)
        }
        awaitingSince?.let { metrics?.awaitingDuration(from.name, ChronoUnit.MILLIS.between(it, clock.instant())) }
        emitTransition(id, userId, from, to)
        if (TransitionTable.isTerminal(to)) {
            finishTerminal(id, userId, to, to, alreadyAtTarget = true)
        } else {
            scope.launch { runCatching { advanceLoop(id, userId) }.onFailure { fail(id, userId, it) } }
        }
        return ResumeOutcome.Ok(to)
    }

    // ---- scripted stage stubs ----

    private fun planStub(
        id: UUID,
        rec: InvestigationRecord,
    ) {
        emitter.emit(
            id,
            Events.planDrafted(PlanDag.newBuilder().setRationale("stub plan").build(), requiresPlanApproval(rec)),
        )
    }

    private fun executeStub(id: UUID) {
        emitter.emit(id, Events.batchLaunched("b1", listOf("S1"), projectedCostUsd = 0.05, maxParallelism = 1))
        emitter.emit(id, Events.stepStarted("S1", "query", "stub query"))
        emitter.emit(id, Events.stepCompleted("S1", "h1", rowCount = 23))
        emitter.emit(id, Events.batchCompleted("b1", listOf("S1"), actualCostUsd = 0.05))
    }

    private fun synthesizeStub(
        id: UUID,
        partial: Boolean,
    ) {
        emitter.emit(id, Events.synthesizerBlockStarted(0, "text"))
        val block =
            Block
                .newBuilder()
                .setBlockId("b0")
                .setRole(BlockRole.PRIMARY)
                .setText(if (partial) "Partial findings (investigation halted)." else "Found 23 customers.")
                .build()
        emitter.emit(id, Events.synthesizerBlockCompleted(0, block))
        emitter.emit(id, Events.synthesizerDone(1))
        val conclusion =
            Conclusion
                .newBuilder()
                .setPrimary(RenderableArtifact.newBuilder().addBlocks(block))
                .setStopReason(if (partial) StopReason.STOP_USER else StopReason.STOP_GOAL_REACHED)
                .setBudgetTruncated(false)
                .setPartial(partial)
                .setConfidence(ConfidenceInfo.newBuilder().setKind(ConfidenceKind.CONFIDENCE_HEURISTIC).setScore(1.0))
                .build()
        emitter.emit(id, Events.conclusion(conclusion))
        investigations.findById(id)?.let { investigations.save(it.copy(conclusionJson = ProtoJson.print(conclusion))) }
    }

    // ---- transition primitives ----

    /**
     * Atomic, idempotent transition `from → to` via the status-conditional update
     * (the same primitive resume uses). Returns whether *this* caller won the race;
     * a loser (a concurrent halt/resume/sweep already moved the status) is a no-op and
     * emits nothing — so duplicate transitions can't double-emit lifecycle/terminal
     * events. On *entering* an AWAITING_* the awaiting clock is stamped immediately
     * after the win (the CAS already serialised the transition, so this follow-up write
     * has no competitor); leaving clears it inside the CAS.
     */
    private fun forward(
        id: UUID,
        userId: String,
        from: Status,
        to: Status,
    ): Boolean {
        TransitionTable.validate(from, to)
        if (!investigations.compareAndSetStatus(id, from.name, to.name)) return false
        if (to in TransitionTable.AWAITING) {
            investigations.findById(id)?.let {
                investigations.save(
                    it.copy(
                        awaitingSince = clock.instant(),
                        awaitingTtlUntil = clock.instant().plus(awaitingTtlHours, ChronoUnit.HOURS),
                    ),
                )
            }
        }
        emitTransition(id, userId, from, to)
        return true
    }

    private fun park(
        id: UUID,
        userId: String,
        from: Status,
        awaiting: Status,
    ) {
        if (!forward(id, userId, from, awaiting)) return
        checkpointer.checkpoint(id, CheckpointReason.AWAITING, SchedulerState())
        metrics?.awaitingEntered(awaiting.name)
        emitter.emit(id, Events.schedulerDrained(awaiting.name))
    }

    private fun emitTransition(
        id: UUID,
        userId: String,
        from: Status,
        to: Status,
    ) {
        emitter.emit(id, Events.statusChanged(from, to))
        emitter.emitLifecycle(id, userId, from, to)
    }

    private fun finishTerminal(
        id: UUID,
        userId: String,
        from: Status,
        to: Status,
        alreadyAtTarget: Boolean = false,
    ) {
        // Idempotent terminal entry: unless we already hold the target (resume won the
        // CAS), claim `from → to` here. If we lose, another path (halt vs driver, sweep
        // vs resume) already finalised it — return without a second `investigation_done`
        // or a FAILED-after-DONE.
        val won = alreadyAtTarget || forward(id, userId, from, to)
        if (!won) return
        haltRequested -= id
        investigations.findById(id)?.let {
            investigations.save(it.copy(finalisedAt = clock.instant()))
            val intentKind = parseResolution(it).resolvedIntent.kind.name
            metrics?.investigationTerminal(
                status = to.name,
                intentKind = intentKind,
                callerKind =
                    runCatching {
                        ProtoJson
                            .parseInto(it.callerJson, Caller.newBuilder())
                            .build()
                            .kind.name
                    }.getOrDefault("KIND_UNSPECIFIED"),
            )
            metrics?.investigationDuration(intentKind, ChronoUnit.MILLIS.between(it.createdAt, clock.instant()))
        }
        emitter.emit(id, Events.investigationDone(to, stubUsage()))
        terminals.getOrPut(id) { CompletableDeferred() }.complete(to)
    }

    private fun failTerminal(
        id: UUID,
        userId: String,
        to: Status,
        messages: List<String>,
    ) {
        investigations.findById(id)?.let {
            investigations.save(it.copy(warningsJson = appendWarnings(it.warningsJson, messages)))
        }
        val cur = investigations.findById(id)?.let { Status.valueOf(it.status) } ?: return
        if (TransitionTable.isLegal(cur, to)) {
            finishTerminal(id, userId, cur, to)
        } else {
            terminals.getOrPut(id) { CompletableDeferred() }.complete(to)
        }
    }

    private fun fail(
        id: UUID,
        userId: String,
        cause: Throwable,
    ) {
        log.error("investigation {} failed", id, cause)
        runCatching {
            val cur = investigations.findById(id)?.let { Status.valueOf(it.status) } ?: return
            if (TransitionTable.isLegal(cur, Status.STATUS_FAILED)) {
                forward(id, userId, cur, Status.STATUS_FAILED)
            }
        }
        terminals.getOrPut(id) { CompletableDeferred() }.complete(Status.STATUS_FAILED)
    }

    // ---- helpers ----

    private fun parseRequest(rec: InvestigationRecord): Investigation =
        ProtoJson.parseInto(rec.requestJson, Investigation.newBuilder()).build()

    private fun parseResolution(rec: InvestigationRecord): ResolutionResult =
        rec.resolutionJson
            ?.let { ProtoJson.parseInto(it, ResolutionResult.newBuilder()).build() }
            ?: ResolutionResult.getDefaultInstance()

    private fun parsePlan(rec: InvestigationRecord): PlanDag =
        rec.planJson
            ?.let { ProtoJson.parseInto(it, PlanDag.newBuilder()).build() }
            ?: PlanDag.getDefaultInstance()

    private fun saveResolution(
        id: UUID,
        resolution: ResolutionResult,
    ) {
        investigations.findById(id)?.let { investigations.save(it.copy(resolutionJson = ProtoJson.print(resolution))) }
    }

    private fun savePlan(
        id: UUID,
        plan: PlanDag,
    ) {
        investigations.findById(id)?.let { investigations.save(it.copy(planJson = ProtoJson.print(plan))) }
    }

    private fun requiresPlanApproval(rec: InvestigationRecord): Boolean {
        val request = parseRequest(rec)
        return when (request.hitlPolicy.planApproval) {
            PlanApprovalPolicy.PLAN_APPROVAL_REQUIRED -> true
            PlanApprovalPolicy.PLAN_APPROVAL_REQUIRED_FOR_DEEP ->
                request.constraints.depthBudget == DepthBudget.DEPTH_DEEP
            else -> false
        }
    }

    private fun userIdOf(rec: InvestigationRecord): String =
        runCatching { ProtoJson.parseInto(rec.callerJson, Caller.newBuilder()).build().userId }.getOrDefault("")

    private fun appendWarnings(
        warningsJson: String,
        messages: List<String>,
    ): String {
        val existing =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(warningsJson)
            }.getOrNull() as? kotlinx.serialization.json.JsonArray
        val arr = existing?.toMutableList() ?: mutableListOf()
        messages.forEach { arr.add(kotlinx.serialization.json.JsonPrimitive(it)) }
        return kotlinx.serialization.json
            .JsonArray(arr)
            .toString()
    }

    private fun stubUsage(): ResourceUsage =
        ResourceUsage
            .newBuilder()
            .setTotalUsd(0.18)
            .setTotalQueryCount(1)
            .setTotalLatencyMs(8200)
            .build()

    private companion object {
        const val MAX_DEEPENING_ROUNDS = 8 // hard backstop above any depth-budget revision cap
        val EMPTY_ANCHOR = HandoffAnchor("", "", "", "", emptyList())
    }
}
