package org.tatrman.kantheon.golem.resolution.ladder

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.ResolutionCoreException
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.ladder.LadderLoop")

// RV-P5.2 — the B-T6-β loop node (RV-10/11/33).
//
//     while (gaps ∧ eligible rungs ∧ budget):
//         next rung per gap kind → hypotheses → re-gate → recompute gaps
//
// LLM rungs propose only; every hypothesis goes through `resolve.gate:v1`; only the core
// binds (RV-7). The Kotlin Golem gets the same STRUCTURAL guarantee golem-py has: no code
// path in this package constructs a `Binding`. `SingleBinderFenceSpec` proves it by grepping
// the source rather than by review.

/** The mutable-but-copied loop state. One turn's worth of ladder accounting. */
data class LadderState(
    val lattice: ResolutionState,
    val gaps: List<GapRecord>,
    val rungsRun: List<String> = emptyList(),
    val llmInvocations: Int = 0,
    val hitlRounds: Int = 0,
    val rungLog: List<RungLogEntry> = emptyList(),
    /** Bindings the GATE returned. Kept beside the lattice; the Golem never builds one. */
    val gatedBindings: List<Binding> = emptyList(),
    /** Set when the ladder had to stop for an infrastructure reason rather than a verdict. */
    val degraded: String? = null,
) {
    fun nextRound(): Int = (rungLog.maxOfOrNull { it.round } ?: 0) + 1
}

/**
 * What a rung produced. A rung PROPOSES — it never returns a binding, and the type says so.
 */
data class RungProposal(
    val hypotheses: List<Hypothesis>,
    /** A rung that could not run (timeout, unparseable output). Not a verdict about a gap. */
    val failure: String? = null,
)

/** One rung's behaviour. Implementations live in `Rungs.kt`; the loop only sees this. */
fun interface Rung {
    suspend fun propose(
        state: LadderState,
        gaps: List<GapRecord>,
        config: LadderConfig,
    ): RungProposal
}

/** What the loop needs from `resolve.gate:v1`. */
fun interface GateCall {
    /** Idempotent by contract (proto: "a retry is a fresh call with the same inputs"). */
    suspend fun gate(
        lattice: ResolutionState,
        hypotheses: List<Hypothesis>,
    ): GateResult
}

/** The gate's answer, already lifted off the proto. */
data class GateResult(
    val gatedBindings: List<Binding>,
    val updatedGaps: List<GapRecord>,
    val rungLogEntry: RungLogEntry?,
    val outcomes: List<HypothesisVerdict>,
)

data class HypothesisVerdict(
    val hypothesis: Hypothesis,
    val accepted: Boolean,
    val reason: String,
    /** The binding it produced, when accepted. Paired with its hypothesis — see [foldGateResult]. */
    val binding: Binding? = null,
)

/**
 * **Fold a gate response into the lattice.** The step that was missing until RV-P5.4 T2, and
 * without it the whole ladder was decorative.
 *
 * ⛑ `regate` kept what the gate returned in `LadderState.gatedBindings`, *beside* the lattice,
 * and `composeStructuredQuestion` reads the lattice. So every binding a rung won — and every
 * binding a user's pin won — was invisible to the answer. Both halves were correct and both
 * were tested; nothing had ever run them end to end with a binding that actually arrived. The
 * shared conformance corpus is what caught it, on its first run, exactly as RV-28 predicts.
 *
 * A gated binding is attached **through its outcome, not by guessing**: `Binding` carries no
 * span, so the only honest route from a binding back to the thing it belongs on is the
 * hypothesis it came from. A binding whose span matches neither a mention nor a value is
 * DROPPED — inventing a mention to hang it on would put a span in the lattice the core never
 * saw.
 *
 * ⚑ **Two layers, not one — and this is a divergence from golem-py, carried upstream.**
 * `apply_gate_result` attaches to MENTIONS only. But a re-gated *value* is the H1′ case: the
 * typo'd `5010O1` is a `ValueFinding` with `valueId: v1` and no mention, so a mention-only fold
 * drops the very correction the hero is about. It goes back as an [Attribution] — which is
 * where a member binding lives (RV-33: `(attribute_ref, member_ref)`, `ref` being
 * `<attribute-ref>#<id>`) — and the mention layer is tried first because a span can only be one
 * or the other. golem-py's suite never caught it: its H1′ asserts the gate's own `outcomes`,
 * never the lattice.
 *
 * `updatedGaps` **replaces** the gap list: the response is "the gaps recomputed after gating",
 * the caller's next input, not a delta to apply.
 */
fun foldGateResult(
    lattice: ResolutionState,
    outcomes: List<HypothesisVerdict>,
    gaps: List<GapRecord>,
    rungLogEntry: RungLogEntry?,
): ResolutionState {
    val builder = lattice.toBuilder()
    outcomes
        .filter { it.accepted && it.binding != null && it.hypothesis.hasSpan() }
        .forEach { outcome ->
            val span = outcome.hypothesis.span
            val binding = outcome.binding ?: return@forEach
            val mention =
                lattice.mentionsList.indexOfFirst { it.span.start == span.start && it.span.end == span.end }
            if (mention >= 0) {
                builder.setMentions(mention, builder.getMentions(mention).toBuilder().addBindings(binding))
                return@forEach
            }
            val value = lattice.valuesList.indexOfFirst { it.span.start == span.start && it.span.end == span.end }
            if (value >= 0) {
                builder.setValues(
                    value,
                    builder
                        .getValues(value)
                        .toBuilder()
                        .addAttributions(
                            Attribution
                                .newBuilder()
                                // `md.dimension.Account.code#501001` → the attribute it restricts.
                                .setAttributeRef(binding.ref.substringBefore('#'))
                                .setBinding(binding),
                        ),
                )
                return@forEach
            }
            log.info(
                "gated binding {} names span '{}', which no mention or value has — dropped",
                binding.ref,
                span.text,
            )
        }
    // Copy, do not alias: protobuf messages are immutable but the LISTS around them are what
    // aliased in golem-py's replay bug, and `clearGaps().addAll` is the copying form.
    builder.clearGaps().addAllGaps(gaps)
    rungLogEntry?.let { builder.addRungLog(it) }
    return builder.build()
}

/**
 * `LOOKUP_FAILED` is the matcher being unaskable, **not** a verdict about a hypothesis
 * (proto, `HypothesisOutcome.reason`). Reporting it as `NO_CANDIDATE` would teach a proposer
 * that a correct guess does not exist. `Gate` is idempotent, so retrying is safe — once.
 */
const val REASON_LOOKUP_FAILED: String = "LOOKUP_FAILED"

/**
 * One pass of the ladder: climb the eligible rungs, gate what they propose, recompute.
 *
 * Returns the state after the pass. The caller (`assessGaps`) decides what happens next —
 * this function never decides to stop, ask or emit, which is what keeps the verdict in one
 * pure place.
 */
suspend fun climbLadder(
    state: LadderState,
    config: LadderConfig,
    profileName: String,
    budgets: Budgets,
    rungs: Map<String, Rung>,
    gate: GateCall,
    otel: OpenTelemetry? = null,
): LadderState {
    var current = state
    val eligible =
        eligibleRungs(current.gaps, current.rungsRun, config, profileName, budgets, current.llmInvocations)

    if (eligible.isEmpty()) {
        log.debug("ladder entered with no eligible rung — zero-rung policy or exhausted budget (RV-27)")
        // With the open Golem's zero-rung default this is the only path through the node,
        // and it says so rather than looping. The SHAPE is present; nothing is on (RV-27).
        return current.copy(
            rungLog =
                current.rungLog +
                    entry(
                        current.nextRound(),
                        rung = "ladder",
                        action = "noop",
                        gapsOpen = openGaps(current.gaps).size,
                    ),
        )
    }

    for (rung in eligible) {
        // ⛑ Re-checked BEFORE each climb, not once at entry. `eligibleRungs` filters against
        // the counters as they stood when the ladder was entered; climbing the whole list
        // afterwards spends a budget of one on three rungs — including `emulated`, the most
        // expensive in the vocabulary. A budget is a cap on spending, not a gate on starting.
        // (golem-py's P4 review found this; the same shape would have been written here.)
        if (!budgets.canRun(rung, current.llmInvocations)) {
            log.info(
                "budget exhausted before {} (llm={}, elapsed_ms={})",
                rung,
                current.llmInvocations,
                budgets.elapsedMs(),
            )
            current =
                current.copy(
                    rungLog =
                        current.rungLog +
                            entry(
                                current.nextRound(),
                                rung = "ladder",
                                action = "halt",
                                gapsOpen = openGaps(current.gaps).size,
                            ),
                )
            break
        }

        val impl =
            rungs[rung] ?: throw LadderConfigException(
                "no implementation for rung '$rung' — the config admits it and dispatch does not",
            )
        val open = openGaps(current.gaps)
        val startedAt = budgets.elapsedMs()

        current = current.copy(rungsRun = current.rungsRun + rung)
        if (rung !in DETERMINISTIC_RUNGS) current = current.copy(llmInvocations = current.llmInvocations + 1)

        val proposal =
            otel.tracedRung(rung, open) {
                try {
                    impl.propose(current, open, config)
                } catch (e: RungRefused) {
                    RungProposal(emptyList(), failure = e.message)
                }
            }

        if (proposal.failure != null || proposal.hypotheses.isEmpty()) {
            log.info("rung {}: {}", rung, proposal.failure ?: "proposed nothing")
            current =
                current.copy(
                    rungLog =
                        current.rungLog +
                            entry(
                                current.nextRound(),
                                rung = rung,
                                action = if (proposal.failure != null) "rung-failed" else "no-proposal",
                                gapsOpen = open.size,
                                elapsedMs = budgets.elapsedMs() - startedAt,
                            ),
                )
            continue
        }

        current = regate(current, proposal.hypotheses, gate, rung, budgets, startedAt, open.size)
    }
    return current
}

/** Where the loop stopped, and the state it stopped in. */
data class LoopOutcome(
    val verdict: Verdict,
    val state: LadderState,
    val rounds: Int,
)

/**
 * The RV-11 loop itself: `while (gaps ∧ eligible rungs ∧ budget)` — assess, climb, recompute,
 * assess again. Terminates when the verdict is anything other than [Verdict.CLIMB].
 *
 * The verdict is computed by the PURE [assess]; this function only applies what it implies.
 * ASK / EMIT / REFUSE are handed back to the caller rather than acted on, because the ask
 * policy and the compose step are P5.3's and a loop that also rendered answers could not be
 * tested against a synthetic lattice.
 *
 * **Termination** rests on two things, both tested: a rung runs at most once per turn, and
 * every LLM rung spends from a finite budget. [maxRounds] is a backstop against neither
 * holding — if it ever fires, that is a bug, so it says so rather than returning quietly.
 */
suspend fun runLadderLoop(
    initial: LadderState,
    config: LadderConfig,
    profileName: String,
    budgets: Budgets,
    rungs: Map<String, Rung>,
    gate: GateCall,
    otel: OpenTelemetry? = null,
    maxRounds: Int = 16,
): LoopOutcome {
    var state = initial
    var rounds = 0
    while (true) {
        val verdict =
            assess(
                gaps = state.gaps,
                rungsRun = state.rungsRun,
                llmInvocations = state.llmInvocations,
                hitlRounds = state.hitlRounds,
                ladder = config,
                profileName = profileName,
                budgets = budgets,
            )
        if (verdict != Verdict.CLIMB) return LoopOutcome(verdict, state, rounds)
        if (rounds >= maxRounds) {
            error(
                "ladder loop did not converge in $maxRounds rounds — a rung is being re-offered " +
                    "or a budget is not being spent (rungsRun=${state.rungsRun}, llm=${state.llmInvocations})",
            )
        }
        rounds++
        val before = state.rungsRun.size
        state = climbLadder(state, config, profileName, budgets, rungs, gate, otel)
        // A pass that ran no rung cannot make progress, and assess() would return CLIMB again.
        if (state.rungsRun.size == before) return LoopOutcome(Verdict.CLIMB, state, rounds)
        if (state.degraded != null) return LoopOutcome(Verdict.ASK, state, rounds)
    }
}

/**
 * Send a rung's hypotheses through `resolve.gate:v1` and fold what survives.
 *
 * ⛑ **The fold COPIES.** golem-py's byte-identical-replay test caught the lattice aliasing
 * the gate response's own gap list, so a later disposition write leaked back into it and a
 * second delivery of the same resume carried no gap notes. Determinism under replay is only
 * real if the fold copies; protobuf messages are immutable, but the *lists* around them are
 * the thing that aliased.
 */
private suspend fun regate(
    state: LadderState,
    hypotheses: List<Hypothesis>,
    gate: GateCall,
    rung: String,
    budgets: Budgets,
    startedAt: Long,
    gapsBefore: Int,
): LadderState {
    val result =
        try {
            gateWithOneRetry(state, hypotheses, gate)
        } catch (e: ResolutionCoreException) {
            log.warn("resolve.gate failed ({}: {}) — the ladder degrades", e.code, e.message)
            return state.copy(
                degraded = "GATE_${e.code}",
                rungLog =
                    state.rungLog +
                        entry(
                            state.nextRound(),
                            rung = rung,
                            action = "gate-failed",
                            gapsOpen = gapsBefore,
                            elapsedMs = budgets.elapsedMs() - startedAt,
                        ),
            )
        }

    // Copy, do not alias: `updatedGaps` is the caller's next input, not a delta to apply,
    // and the list we keep must not be the one the response owns.
    val gaps = result.updatedGaps.toList()
    val refusedHypotheses =
        result.outcomes.filterNot { it.accepted }.map { it.hypothesis }

    // (e) The gate's outcomes are CONSUMED, not discarded: a refused hypothesis is recorded
    // on its gap so the rung does not re-propose it verbatim next round.
    val withTried =
        gaps.map { gap ->
            val mine = refusedHypotheses.filter { it.span == gap.span }
            if (mine.isEmpty()) gap else gap.toBuilder().addAllHypothesesTried(mine).build()
        }

    // ⚑ The CORE writes its own rung-log entry and we prefer it — the P4 live drill found
    // that a client-side fallback always set a `note` the real core does not, which made the
    // two distinguishable. Ours is used only when the gate returned none.
    val logEntry =
        result.rungLogEntry ?: entry(
            state.nextRound(),
            rung = rung,
            action = "regate",
            gapsOpen = openGaps(withTried).size,
            elapsedMs = budgets.elapsedMs() - startedAt,
        )

    return state.copy(
        // The fold is what makes a gated binding reach the answer (see `foldGateResult`).
        lattice = foldGateResult(state.lattice, result.outcomes, withTried, logEntry),
        gaps = withTried,
        gatedBindings = state.gatedBindings + result.gatedBindings,
        rungLog = state.rungLog + logEntry,
    )
}

/**
 * (f) `LOOKUP_FAILED` ⇒ retry once, then degrade. Never treated as `NO_CANDIDATE`.
 *
 * The retry is safe because `Gate` is stateless and idempotent by contract — the caller
 * carries the lattice, so a retry is a fresh call with the same inputs.
 */
private suspend fun gateWithOneRetry(
    state: LadderState,
    hypotheses: List<Hypothesis>,
    gate: GateCall,
): GateResult {
    val first = gate.gate(state.lattice, hypotheses)
    if (first.outcomes.none { it.reason == REASON_LOOKUP_FAILED }) return first

    log.info("gate reported {} — retrying once (Gate is idempotent)", REASON_LOOKUP_FAILED)
    val second = gate.gate(state.lattice, hypotheses)
    if (second.outcomes.none { it.reason == REASON_LOOKUP_FAILED }) return second

    // Still unaskable. Degrade — and crucially do NOT let the outcome reach a gap as though
    // the vocabulary had been searched and come up empty.
    throw ResolutionCoreException(REASON_LOOKUP_FAILED, "the matcher could not be asked, twice")
}

/** A rung that declines to run. `emulated` uses it; a timeout would too. */
class RungRefused(
    message: String,
) : RuntimeException(message)

/**
 * ⚑ **`RungLogEntry` has no `note` field**, and that shapes how the log tells its story.
 * golem-py added one Python-side, so its entries carry prose; the proto's do not (fields:
 * round · rung · action · mention_ids · value_ids · hypotheses · bindings_added · gaps_open ·
 * elapsed_ms). Rather than smuggle prose into `action`, the **verb** carries the machine-
 * readable reason — `noop` · `halt` · `climb` · `no-proposal` · `rung-failed` · `regate` ·
 * `gate-failed` — and the human sentence goes to the log and the span, where it belongs.
 * Recorded for Bora: the two shells' rung logs are therefore not field-identical, and a
 * `note` on the proto is the cheap fix if that matters at P5.4's conformance tier.
 */
internal fun entry(
    round: Int,
    rung: String,
    action: String,
    gapsOpen: Int,
    elapsedMs: Long = 0,
    bindingsAdded: Int = 0,
): RungLogEntry =
    RungLogEntry
        .newBuilder()
        .setRound(round)
        .setRung(rung)
        .setAction(action)
        .setGapsOpen(gapsOpen)
        .setElapsedMs(elapsedMs)
        .setBindingsAdded(bindingsAdded)
        .build()
