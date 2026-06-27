package org.tatrman.kantheon.golem.graph

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
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
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
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
    val plan: MiniPlan? = null,
    val violations: List<PlanViolation> = emptyList(),
    val decision: GateDecision? = null,
    val execution: ExecutionResult? = null,
    val clarification: FormatEnvelope? = null,
    val outcome: TurnOutcome? = null,
)

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

        // param_fill resume re-enters at `gate` with the already-bound plan — it skips compose
        // (the plan is fixed) but is **re-validated and re-gated** before execute (B2): a resume
        // token is a server-trusted blob whose authorization decays, so HMAC integrity does not
        // substitute for re-running PlanValidator + the confidence gate on the bound plan.
        edge(nodeStart forwardTo gate onCondition { it.resumeParamFill && it.plan != null })
        edge(nodeStart forwardTo resolveSelection onCondition { !(it.resumeParamFill && it.plan != null) })
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
