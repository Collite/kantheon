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
import org.tatrman.kantheon.golem.resolution.AssessedGaps
import org.tatrman.kantheon.golem.resolution.CoreDegrade
import org.tatrman.kantheon.golem.resolution.ResolutionCoreClient
import org.tatrman.kantheon.golem.resolution.ResolutionDeps
import org.tatrman.kantheon.golem.resolution.ResolutionProvenance
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.TurnFacts
import org.tatrman.kantheon.golem.resolution.askStep
import org.tatrman.kantheon.golem.resolution.assessGapsStep
import org.tatrman.kantheon.golem.resolution.callResolutionCoreStep
import org.tatrman.kantheon.golem.resolution.compose.FallThroughReason
import org.tatrman.kantheon.golem.resolution.fastPathStep
import org.tatrman.kantheon.golem.resolution.hitl.SignedOption
import org.tatrman.kantheon.golem.resolution.ladder.Verdict
import org.tatrman.kantheon.golem.resolution.selectionStep
import org.tatrman.kantheon.golem.resolution.tracedResolutionCore
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.resolver.v1.AwaitingClarification
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
    /** RV-P5.4 — the core's signed options + its HMAC resume token, when it clarified.
     *  Lifted off `ResolveResponse.awaiting`; read by [turnFactsFor], which is the only way
     *  an ask can offer the core's own answers rather than none. */
    val awaitingClarification: AwaitingClarification? = null,
    /** RV-P5.3 — the per-turn facts the resolution path reads (locale, profile, prior intent,
     *  the core's signed options). Assembled at `callResolutionCore`, read by `assessGaps`. */
    val turnFacts: TurnFacts? = null,
    /** RV-P5.3 — where the ladder stopped, and the intent the three exits route on. */
    val assessed: AssessedGaps? = null,
    /** RV-P5.3 — how the resolution path ended: answered, paused on an ask, or refused.
     *  Null on a turn with no lattice, which is the signal the LEGACY chain runs instead. */
    val turnEnd: TurnEnd? = null,
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
        awaitingClarification = result.awaiting,
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
 * RV-P5.3 — the per-turn facts, assembled once where the request is still in hand.
 *
 * ⚑ **`conversationId` is the P5.1 carry, now closed** — `golem/v1.GolemContext` gained
 * `conversation_id` (field 7, additive) and `GolemRequestFactory` fills it from the BFF's
 * thread. The fallback chain below is what a turn dispatched by an older BFF gets: a
 * correlation id scopes the core's resume token correctly but does NOT outlive a turn, so
 * RV-17's one-pool budget silently becomes one-pool-per-turn. That degradation is logged
 * rather than hidden, because an ask budget that never runs out looks like a working system.
 */
fun turnFactsFor(
    state: GolemTurnState,
    profileName: String,
): TurnFacts {
    val conversationId =
        state.request.context.conversationId
            .ifBlank {
                log.info(
                    "no conversation_id on the request (pre-RV-P5.3 BFF?) — the ask budget will " +
                        "scope to this turn rather than to the conversation (RV-17)",
                )
                state.request.caller.correlationId
                    .ifBlank { state.request.id }
            }
    return TurnFacts(
        question = state.request.question,
        conversationId = conversationId,
        callerSubject = state.userId,
        tenantId = state.tenantId,
        locale =
            state.request.context.locale
                .ifBlank { "cs" },
        profileName = profileName,
        bearer = state.bearer,
        // Themis's verdict, verbatim, as the intent prior (RV-P5.3 T1). Absent on a
        // KEEP_TOGETHER dispatch, which is exactly the vacuum the operator evidence fills.
        priorIntent = if (state.request.hasResolvedIntent()) state.request.resolvedIntent else null,
        // ⛑ RV-P5.4 T2 — the CORE's options and the CORE's token, never invented here. Both
        // empty when it did not clarify, which is the honest "I don't recognise this word,
        // what does it mean?" ask rather than a fabricated option list.
        signedOptions = state.awaitingClarification?.let { SignedOption.from(it) } ?: emptyList(),
        resumeToken = state.awaitingClarification?.resumeToken.orEmpty(),
    )
}

/**
 * `assessGaps` node — RV-11's loop. A no-op without a lattice, which is what routes the turn
 * back onto the legacy chain.
 */
suspend fun assessGapsNode(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val lattice = state.lattice ?: return state
    val resolution = deps.resolution ?: return state
    val facts = turnFactsFor(state, deps.profileName(state))
    return state.copy(turnFacts = facts, assessed = assessGapsStep(lattice, facts, resolution))
}

/** `fastPath` node — the γ predicate, compose, the query door, the answer envelope. */
suspend fun fastPathNode(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val assessed = state.assessed ?: return state
    val facts = state.turnFacts ?: return state
    val resolution = deps.resolution ?: return state
    val end = fastPathStep(assessed, facts, resolution)
    // A fall-through is not an outcome yet — `selection` decides. Leaving `outcome` unset here
    // is what stops a refused turn being reported as EXECUTED.
    return state.copy(
        turnEnd = end,
        outcome = if (end is TurnEnd.Answered) TurnOutcome.EXECUTED else state.outcome,
    )
}

/** `askGap` node — RV-17's one-pool ask; the turn pauses on a snapshot. */
fun askGapNode(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val assessed = state.assessed ?: return state
    val facts = state.turnFacts ?: return state
    val resolution = deps.resolution ?: return state
    val end = askStep(assessed, facts, resolution)
    // `chooseAsk` can decline (askable by policy, but nothing load-bearing) — that falls
    // through to selection rather than pausing the turn on a question worth nothing.
    return state.copy(
        turnEnd = end,
        outcome = if (end is TurnEnd.Paused) TurnOutcome.CLARIFY else state.outcome,
    )
}

/**
 * `selection` node — the RV-P6 delegation seam, a **stub that refuses by name** in this
 * phase. Everything the fast path does not take lands here.
 */
fun selectionNode(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val assessed = state.assessed ?: return state
    val resolution = deps.resolution ?: return state
    // Reached either from `fastPath` (γ did not fire) or straight from `assessGaps` (the
    // ladder REFUSEd under `strict`). The second case has no FellThrough to carry, so it is
    // built here — one refusal per turn either way.
    val fell =
        state.turnEnd as? TurnEnd.FellThrough
            ?: TurnEnd.FellThrough(
                FallThroughReason.GAPS_OPEN,
                "the ladder exhausted and the profile is strict",
                assessed.ladder,
            )
    return state.copy(turnEnd = selectionStep(fell, resolution), outcome = TurnOutcome.FAILED)
}

/**
 * Where a turn goes next out of the RV nodes.
 *
 * ⛑ **The routing predicates live here, not inline in the edges**, and that is the fix for a
 * real divergence: `GraphWalk.kt` (the test-side topology, shared by the component pass and the
 * conformance runner) claimed to mirror the edges "exactly" and did not — it sent a CLIMB
 * verdict and a null `turnEnd` to `selection` where the shipped graph had no edge at all for
 * the first and went to `nodeFinish` for the second. The conformance tier was therefore
 * asserting a topology that was not the one in production. Both now call these functions, so
 * the conditions cannot drift; what remains different is only that the harness has no legacy
 * chain to forward to, which is a property of the harness and is stated where it happens.
 */
internal enum class RvRoute { LEGACY, FAST_PATH, ASK, SELECTION, FINISH }

/** The four exits out of `assessGaps`, one per verdict, plus "no lattice ⇒ the legacy chain". */
internal fun routeAfterAssess(state: GolemTurnState): RvRoute =
    when (state.assessed?.verdict) {
        null -> RvRoute.LEGACY
        Verdict.EMIT -> RvRoute.FAST_PATH
        Verdict.ASK -> RvRoute.ASK
        // REFUSE is the ladder exhausting under `strict`. CLIMB is not supposed to reach a
        // caller at all (`runLadderLoop` settles it), but an edge is cheaper than a dead-ended
        // strategy, and `selection` is where a turn with gaps left open belongs either way.
        Verdict.REFUSE, Verdict.CLIMB -> RvRoute.SELECTION
    }

/**
 * Out of `fastPath` / `askGap`: a fall-through goes to `selection`, an answer or a pause is
 * done. A null `turnEnd` also goes to `selection` — it means an exit node found no deps to run
 * with, and finishing a turn that reached no end would report it as neither answered nor refused.
 */
internal fun routeAfterExit(state: GolemTurnState): RvRoute =
    if (state.turnEnd is TurnEnd.FellThrough || state.turnEnd == null) RvRoute.SELECTION else RvRoute.FINISH

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
    /** RV-P5.3 — the ladder, the rungs, the gate, the skill library, the door, the snapshot
     *  store and the feedback sink. **Null by default and null means the three RV nodes are
     *  inert**, exactly as [resolutionCore] is: the two are wired together or not at all. */
    val resolution: ResolutionDeps? = null,
    /** Which ladder profile this turn runs under. CHAT_QUICK is the Golem's own posture —
     *  a Golem turn is a chat turn by definition; INVESTIGATION_DEEP is Pythia's, and a Golem
     *  that classified its own turns as DEEP would quietly grant itself a 6× LLM budget.
     *  A supplier so an estate can override per-request without a second deps object. */
    val profileName: (GolemTurnState) -> String = { "CHAT_QUICK" },
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

        // RV-P5.3 — the resolution path. `assessGaps` is RV-11's loop and the resume rejoin
        // point (the edge P5.1 promised P5.2 would re-point, re-pointed here because P5.2
        // shipped the loop as a library and left the graph alone).
        //
        // Three exits, one per verdict, as separate nodes rather than one branch inside a
        // node: the topology IS the design's claim — a DATA_QUERY with no gaps reaches the
        // door without passing through selection, and "without passing through" is only a
        // testable statement if selection is somewhere to pass through.
        val assessGaps by node<GolemTurnState, GolemTurnState>("assessGaps") { state ->
            assessGapsNode(state, deps)
        }
        val fastPath by node<GolemTurnState, GolemTurnState>("fastPath") { state ->
            fastPathNode(state, deps)
        }
        val askGap by node<GolemTurnState, GolemTurnState>("askGap") { state ->
            askGapNode(state, deps)
        }
        val selection by node<GolemTurnState, GolemTurnState>("selection") { state ->
            selectionNode(state, deps)
        }

        // param_fill resume re-enters at `gate` with the already-bound plan — it skips compose
        // (the plan is fixed) but is **re-validated and re-gated** before execute (B2): a resume
        // token is a server-trusted blob whose authorization decays, so HMAC integrity does not
        // substitute for re-running PlanValidator + the confidence gate on the bound plan.
        edge(nodeStart forwardTo gate onCondition { it.resumeParamFill && it.plan != null })
        edge(nodeStart forwardTo callCore onCondition { !(it.resumeParamFill && it.plan != null) })
        edge(callCore forwardTo assessGaps)

        // ⚑ Every condition below is [routeAfterAssess] / [routeAfterExit] — the SAME functions
        // `GraphWalk.kt` walks, so the tested topology and the shipped one cannot disagree.
        //
        // No lattice — the core is unwired, or the turn degraded — so the LEGACY chain runs,
        // byte-for-byte as it did before RV. This is the one edge that keeps P5.1's "additive,
        // and inert unless wired" promise true now that the path downstream is no longer empty.
        edge(assessGaps forwardTo resolveSelection onCondition { routeAfterAssess(it) == RvRoute.LEGACY })
        edge(assessGaps forwardTo fastPath onCondition { routeAfterAssess(it) == RvRoute.FAST_PATH })
        edge(assessGaps forwardTo askGap onCondition { routeAfterAssess(it) == RvRoute.ASK })
        edge(assessGaps forwardTo selection onCondition { routeAfterAssess(it) == RvRoute.SELECTION })

        // γ did not fire (not DATA_QUERY, or compose refused) ⇒ the residue falls through.
        edge(fastPath forwardTo selection onCondition { routeAfterExit(it) == RvRoute.SELECTION })
        edge(fastPath forwardTo nodeFinish onCondition { routeAfterExit(it) == RvRoute.FINISH })
        edge(askGap forwardTo selection onCondition { routeAfterExit(it) == RvRoute.SELECTION })
        edge(askGap forwardTo nodeFinish onCondition { routeAfterExit(it) == RvRoute.FINISH })
        edge(selection forwardTo nodeFinish)

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
