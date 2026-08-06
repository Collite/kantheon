package org.tatrman.kantheon.golem.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PlanSource as EnvelopePlanSource
import org.tatrman.kantheon.golem.context.ModelSnapshot
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.golem.execution.ExecutionResult
import org.tatrman.kantheon.golem.execution.MiniPlanExecutor
import org.tatrman.kantheon.golem.execution.ResolvedPriorView
import org.tatrman.kantheon.golem.execution.SelectionResolver
import org.tatrman.kantheon.golem.format.InvestigateChips
import org.tatrman.kantheon.golem.plan.GateDecision
import org.tatrman.kantheon.golem.plan.GateThresholds
import org.tatrman.kantheon.golem.plan.GolemModels
import org.tatrman.kantheon.golem.plan.PlanComposer
import org.tatrman.kantheon.golem.plan.PlanDecodeException
import org.tatrman.kantheon.golem.plan.PlanValidator
import org.tatrman.kantheon.golem.plan.PlanViolation
import org.tatrman.kantheon.golem.plan.bindSelectionArgs
import org.tatrman.kantheon.golem.plan.gatePlan
import org.tatrman.kantheon.golem.resolution.CoreDegrade
import org.tatrman.kantheon.golem.resolution.ResolutionCoreClient
import org.tatrman.kantheon.golem.resolution.ResolutionProvenance
import org.tatrman.kantheon.golem.resolution.callResolutionCoreStep
import org.tatrman.kantheon.golem.resolution.tracedResolutionCore
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.ResolutionState
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.graph.GolemGraph")

/** Terminal disposition of a turn (the skeleton stops here; Stage 2.4 fills execute/format). */
enum class TurnOutcome { EXECUTED, CLARIFY, FAILED }

/**
 * Per-turn state threaded through the Golem graph. Immutable — each node returns a
 * copy. Stage 2.3 carries the plan + gate decision; execution artifacts + the
 * envelope land in Stage 2.4.
 */
data class GolemTurnState(
    val request: GolemRequest,
    val bearer: String? = null,
    /** The admitted caller's identity — scopes history lookups (selection / prior-view) to the
     *  caller so a turn can't read another user's or tenant's rows (H2). */
    val userId: String = "",
    val tenantId: String = "",
    val model: ModelSnapshot? = null,
    /** Resolved AMEND/DRILL prior view (null for a fresh question) — fed to the
     *  composer so the plan rehydrates the pattern/args/sql the user is amending. */
    val priorView: ResolvedPriorView? = null,
    /** Rows a row-detail selection referred to, resolved from history (S2.4 §10 Δ4);
     *  empty when the turn carries no selection. */
    val selectedRows: List<JsonObject> = emptyList(),
    /** The first selected row flattened to `{column: value}` — the source unfilled
     *  pattern params are bound from (`_bind_selection_args`). */
    val selectionContext: JsonObject = JsonObject(emptyMap()),
    /** A param_fill resume re-enters with the bound plan and skips the cascade
     *  (`nodeStart → execute` shortcut, Δ2). */
    val resumeParamFill: Boolean = false,
    /** RV-P5.1 — the annotation lattice from one `resolve.bind:v1` call. Null when the
     *  resolution core is not wired (the default) or when the turn degraded. Nothing reads
     *  it yet: P5.2 loops over its gaps, P5.3 composes from its bindings. */
    val lattice: ResolutionState? = null,
    /** RV-39 layer tuple + S-1 engine identity for [lattice]. */
    val resolutionProvenance: ResolutionProvenance? = null,
    /** RS-7 capability matrix the core echoed — what actually backed this resolve. */
    val resolutionCapabilities: Capabilities? = null,
    /** Set when the core door failed. A degraded turn has no [lattice] and says so. */
    val coreDegrade: CoreDegrade? = null,
    val plan: MiniPlan? = null,
    val violations: List<PlanViolation> = emptyList(),
    val decision: GateDecision? = null,
    val execution: ExecutionResult? = null,
    val clarification: FormatEnvelope? = null,
    val outcome: TurnOutcome? = null,
)

/**
 * RV-P5.1 — `callResolutionCore`. One gRPC to `resolve.bind:v1`; the whole annotation
 * lattice lands on the turn.
 *
 * **Additive, and inert unless wired** (ruling (A), 2026-08-06). The six Themis nodes this
 * replaces are not deleted — `agents/themis` runs untouched until RV-P6 retires it — and
 * nothing downstream reads the lattice yet. With no client the node is a no-op: same
 * "an estate that does not have it is unaffected" posture the RV-P3 lexicon mounts took,
 * because otherwise every turn in every estate pays a gRPC hop (or a timeout where no
 * resolver is deployed) to fill a field nobody reads. The node stays in the graph either
 * way — see the strategy for why one topology beats one saved hop.
 */
suspend fun callResolutionCoreNodeStep(
    state: GolemTurnState,
    client: ResolutionCoreClient,
    referenceDatetime: String,
    otel: OpenTelemetry? = null,
): GolemTurnState {
    val result =
        otel.tracedResolutionCore {
            callResolutionCoreStep(
                question = state.request.question,
                // ⚑ FOUND at T3, and it lands on P5.3 T4: **golem has no conversation id.**
                // `golem/v1` carries none, and `golem_turns` keys rows by request/user/tenant
                // only — the closest thing is `Caller.correlation_id`. That is enough for the
                // core (which uses it to scope a resume token) but NOT for RV-17's "one ask
                // budget per conversation", which needs an identifier that outlives a turn.
                // Recorded rather than papered over; the fallback keeps this call well-formed.
                conversationId =
                    state.request.caller.correlationId
                        .ifBlank { state.request.id },
                locale =
                    state.request.context.locale
                        .ifBlank { "cs" },
                referenceDatetime = referenceDatetime,
                tenant = state.tenantId,
                callerSubject = state.userId,
                client = client,
            )
        }
    return state.copy(
        lattice = result.lattice,
        resolutionProvenance = result.provenance,
        resolutionCapabilities = result.capabilities,
        coreDegrade = result.degrade,
    )
}

/**
 * The `callResolutionCore` node body as the strategy runs it — a no-op when no core is
 * wired. Separate from [callResolutionCoreNodeStep] so the wired and unwired paths are both
 * reachable from a test without standing up an `AIAgent`.
 */
suspend fun callResolutionCoreNode(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState =
    deps.resolutionCore
        ?.let { client -> callResolutionCoreNodeStep(state, client, deps.referenceDatetime(), deps.otel) }
        ?: state

/**
 * resolveSelection node (S2.4 §10 Δ4) — runs before plan composition. Resolves a
 * row-detail `{bubble_id, row_indices}` reference against `golem_turns` history into
 * `selected_rows` + a flattened `selection_context`; a stale / out-of-range selection
 * is a no-op (the turn proceeds without it).
 */
fun resolveSelectionStep(
    state: GolemTurnState,
    resolver: SelectionResolver,
): GolemTurnState {
    val selection = if (state.request.context.hasSelection()) state.request.context.selection else null
    val resolved = resolver.resolve(selection, state.userId, state.tenantId) ?: return state
    return state.copy(selectedRows = resolved.selectedRows, selectionContext = resolved.selectionContext)
}

/** composePlan node — LLM plan composition; a decode failure leaves plan=null (→ clarify).
 *  After composing, unfilled pattern params are bound from a row-detail selection
 *  (`_bind_selection_args`) — explicit args always win. */
suspend fun composePlanStep(
    state: GolemTurnState,
    composer: PlanComposer,
): GolemTurnState =
    try {
        val plan = composer.compose(state.request, state.model, priorViewHint(state.priorView))
        state.copy(plan = bindSelectionArgs(plan, state.selectionContext, state.model))
    } catch (e: PlanDecodeException) {
        log.info("plan compose failed to decode ({}) — routing to clarification", e.message)
        state.copy(plan = null)
    }

/**
 * A compact `prior_view` hint for the composer prompt on AMEND/DRILL — the resolved
 * pattern/args/sql the new plan should build on. Null (→ the composer's default
 * "<present>"/empty behaviour) when this is a fresh question.
 */
private fun priorViewHint(prior: ResolvedPriorView?): String? {
    if (prior == null) return null
    val parts =
        buildList {
            prior.patternId?.let { add("pattern_id=$it") }
            if (prior.argsJson.isNotBlank() && prior.argsJson != "{}") add("args=${prior.argsJson}")
            prior.sql?.let { add("sql=$it") }
        }
    return parts.joinToString("; ").ifBlank { "<present>" }
}

/**
 * gatePlan node — validate then gate. No plan (decode failure) or validation
 * violations force a clarification; otherwise the confidence gate decides.
 */
fun gatePlanStep(
    state: GolemTurnState,
    validator: PlanValidator,
    thresholds: GateThresholds,
): GolemTurnState {
    val plan = state.plan ?: return state.copy(decision = GateDecision.Clarify("plan could not be composed", 0.0))
    val validation = validator.validate(plan, state.model)
    if (!validation.isValid) {
        return state.copy(
            violations = validation.violations,
            decision =
                GateDecision.Clarify(
                    "plan failed validation: ${validation.violations.firstOrNull()?.message}",
                    plan.confidence,
                ),
        )
    }
    return state.copy(decision = gatePlan(plan, thresholds))
}

/** execute node — run the gated mini-plan through the [MiniPlanExecutor]. */
suspend fun executeStep(
    state: GolemTurnState,
    executor: MiniPlanExecutor,
): GolemTurnState {
    val plan = state.plan ?: return state.copy(outcome = TurnOutcome.FAILED)
    val result = executor.execute(plan, state.request, state.model, state.bearer)
    return state.copy(execution = result, outcome = TurnOutcome.EXECUTED)
}

/**
 * emitClarification node — a minimal Golem-issued clarification envelope (plaintext
 * + `plan_source = CLARIFICATION`). The HMAC resume token + typed options land in
 * Stage 3.2; this carries the gate's reason so the BFF can show the user a prompt.
 */
fun emitClarificationStep(state: GolemTurnState): GolemTurnState {
    val reason = (state.decision as? GateDecision.Clarify)?.reason ?: "Potřebuji upřesnění."
    val b =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(UUID.randomUUID().toString())
            .setTurnId(state.request.id)
            .setText(reason)
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT))
            .setPlanSource(EnvelopePlanSource.CLARIFICATION)
            .setAgentId(state.request.golemId)
    // PD-1: an analytical intent that fails Golem's gate gets an escalation affordance to Pythia
    // (Golem never calls Pythia — the BFF re-issues on click). May ride alongside the clarification.
    // currentView is intentionally null here: a gate-failed clarification has produced no rendered
    // view to snapshot. The InvestigateChip's handoff.view is populated only when the chip rides a
    // partial answer (a path that does not exist on this clarify node).
    InvestigateChips.maybe(state.request, gateFailed = true, currentView = null)?.let { b.addChips(it) }
    return state.copy(clarification = b.build(), outcome = TurnOutcome.CLARIFY)
}

/** Dependencies the strategy closure captures. */
data class GolemGraphDeps(
    val composer: PlanComposer,
    val validator: PlanValidator,
    val miniPlanExecutor: MiniPlanExecutor,
    val promptExecutor: PromptExecutor,
    val thresholds: GateThresholds = GateThresholds(),
    /** Resolves row-detail selections against history (S2.4 §10 Δ4). Defaults to a
     *  no-op (skeleton boot / tests without history). */
    val selectionResolver: SelectionResolver = SelectionResolver.NONE,
    /** RV-P5.1 — the resolution core. **Null by default, and null means the node is not in
     *  the graph at all**: nothing downstream reads the lattice until P5.2/P5.3, so an
     *  estate with no resolver deployed must not pay a gRPC hop (or a timeout) per turn. */
    val resolutionCore: ResolutionCoreClient? = null,
    /** Grounding anchor handed to the core. A supplier, not a value: a fixed clock would
     *  make every turn resolve "last 12 months" against the same instant. */
    val referenceDatetime: () -> String = { Instant.now().toString() },
    /** The process SDK, so `golem.callResolutionCore` joins the turn's trace. Null = off. */
    val otel: OpenTelemetry? = null,
)

/**
 * The Golem turn graph (architecture §4): `composePlan → gatePlan → {execute |
 * emitClarification}`. Skeleton at Stage 2.3 — the execute/clarify nodes are
 * placeholders filled in Stage 2.4. Mirrors the Themis node-port pattern.
 */
fun buildGolemGraph(deps: GolemGraphDeps): AIAgentGraphStrategy<GolemTurnState, GolemTurnState> =
    strategy("golem") {
        val resolveSelection by node<GolemTurnState, GolemTurnState>("resolveSelection") { state ->
            resolveSelectionStep(state, deps.selectionResolver)
        }
        val compose by node<GolemTurnState, GolemTurnState>("composePlan") { state ->
            composePlanStep(state, deps.composer)
        }
        val gate by node<GolemTurnState, GolemTurnState>("gatePlan") { state ->
            gatePlanStep(state, deps.validator, deps.thresholds)
        }
        val execute by node<GolemTurnState, GolemTurnState>("execute") { state ->
            executeStep(state, deps.miniPlanExecutor)
        }
        val clarify by node<GolemTurnState, GolemTurnState>(
            "emitClarification",
        ) { state -> emitClarificationStep(state) }

        // RV-P5.1 — `callResolutionCore` runs FIRST on a fresh turn (detailed-design §4: the
        // core call heads the merged-Golem chain) and is transparent to everything after it.
        // Additive, per ruling (A): no existing node reads the lattice, so the chain below is
        // unchanged whether or not a core answers.
        //
        // **A null client makes it a no-op rather than removing it from the graph.** One graph
        // shape is worth more than one saved hop: an estate with no resolver deployed must not
        // pay a gRPC call — or a timeout — per turn to fill a field nobody reads yet, and a
        // conditional TOPOLOGY would mean the shape under test is not the shape that ships.
        //
        // The param_fill resume shortcut deliberately skips it: a resume carries a bound plan,
        // and re-resolving its text is work with no consumer. ⚑ RV-11 says resume rejoins at
        // `assessGaps` — that node is P5.2's, and this is the edge P5.2 re-points.
        val callCore by node<GolemTurnState, GolemTurnState>("callResolutionCore") { state ->
            callResolutionCoreNode(state, deps)
        }

        // param_fill resume re-enters at `gate` with the already-bound plan — it skips compose
        // (the plan is fixed) but is **re-validated and re-gated** before execute (B2): a resume
        // token is a server-trusted blob whose authorization decays, so HMAC integrity does not
        // substitute for re-running PlanValidator + the confidence gate on the bound plan.
        edge(nodeStart forwardTo gate onCondition { it.resumeParamFill && it.plan != null })
        edge(nodeStart forwardTo callCore onCondition { !(it.resumeParamFill && it.plan != null) })
        edge(callCore forwardTo resolveSelection)
        edge(resolveSelection forwardTo compose)
        edge(compose forwardTo gate)
        edge(gate forwardTo execute onCondition { it.decision is GateDecision.Execute })
        edge(gate forwardTo clarify onCondition { it.decision is GateDecision.Clarify })
        edge(execute forwardTo nodeFinish)
        edge(clarify forwardTo nodeFinish)
    }

/** Run the graph end-to-end via [AIAgent.run] (nodes call their own clients; the
 *  agent executor satisfies the framework). */
suspend fun runGolemGraph(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val agent =
        AIAgent(
            promptExecutor = deps.promptExecutor,
            strategy = buildGolemGraph(deps),
            agentConfig =
                AIAgentConfig(
                    prompt = prompt("golem") { },
                    model = GolemModels.Cheap,
                    maxAgentIterations = 20,
                ),
        )
    return agent.run(state)
}
