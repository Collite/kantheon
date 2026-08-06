package org.tatrman.kantheon.golem.resolution.compose

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.ResolutionProvenance
import org.tatrman.kantheon.golem.resolution.intent.TurnIntent
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.blockingGaps
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.ResolutionState

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.compose.FastPath")

// RV-P5.3 T2 — the G-T1-γ fast path. DATA_QUERY intent ∧ gap-free lattice ⇒ compose straight
// to the query door. NO selection step, 0 LLM (H1/H5).

/** Why the γ predicate did not fire. Named, because the residue is routed on it. */
enum class FallThroughReason {
    /** RCA / FORECAST / SIMULATION, or nothing said. H4 territory. */
    NOT_DATA_QUERY,

    /** The P5.2 loop ended with gaps still open — those gaps decide, not this predicate. */
    GAPS_OPEN,

    /** No lattice at all: the core is unwired, or the turn degraded (P5.1). */
    NO_LATTICE,

    /** γ fired, and compose refused anyway (an unknown `op:`, nothing to select). */
    COMPOSE_REFUSED,
}

/** What γ decided. */
sealed interface FastPathVerdict {
    data class Compose(
        val question: StructuredQuestion,
    ) : FastPathVerdict

    data class FallThrough(
        val reason: FallThroughReason,
        val detail: String = "",
    ) : FastPathVerdict
}

/**
 * **The γ predicate.** Two conjuncts and no third:
 *
 *  1. the intent is DATA_QUERY-class (`resolution/intent/TurnIntent.kt`), and
 *  2. the lattice has no open gaps **after the P5.2 loop**.
 *
 * Both halves are load-bearing and neither is sufficient. Intent alone would send a gapped
 * question to the door and answer it about the wrong entity; gap-free alone would send *"Proč
 * klesly tržby?"* — which can resolve perfectly and still be a question about causes — down a
 * path that only knows how to select rows.
 *
 * ⚑ **"gap-free" means no BLOCKING gap, not no gap** — and the difference is H5.
 *
 * The plan writes the conjunct as `gaps == []`, which reads as `openGaps(…).isEmpty()` and is
 * wrong in a way that only one hero exposes. *"Ukaž vývoj nákladů střediska a porovnej s
 * plánem"* leaves an honest `G1_UNBOUND` on *plánem* in FILTER position: not load-bearing, so
 * [blockingGaps] excludes it, so the ladder's own [org.tatrman.kantheon.golem.resolution.ladder.assess]
 * returns EMIT — and a γ predicate that then refused would throw away four correct bindings to
 * protect one unbindable filter. The gap rides onto the answer as `gaps_carried[]` instead,
 * which is exactly what RV-19 is for and what golem-py's H5 asserts.
 *
 * Two gaps are therefore invisible here for two different reasons: a **settled** one
 * (`USER_CONFIRMED_UNKNOWN`, or closed by the loop) is not open, and a **carryable** one is
 * open but does not block. Treating either as a reason to refuse would make the fast path
 * unreachable on any turn that ever had a gap.
 */
fun fastPathVerdict(
    intent: TurnIntent,
    lattice: ResolutionState?,
    gaps: List<GapRecord>,
    library: LayeredSkillLibrary,
    ladder: LadderConfig,
): FastPathVerdict {
    if (lattice == null) return FastPathVerdict.FallThrough(FallThroughReason.NO_LATTICE)
    if (!intent.isDataQuery) {
        return FastPathVerdict.FallThrough(FallThroughReason.NOT_DATA_QUERY, intent.rationale)
    }
    val blocking = blockingGaps(gaps, ladder)
    if (blocking.isNotEmpty()) {
        return FastPathVerdict.FallThrough(
            FallThroughReason.GAPS_OPEN,
            blocking.joinToString(", ") { "${it.kind.name.removePrefix("GAP_KIND_")}(${it.span.text})" },
        )
    }
    return try {
        FastPathVerdict.Compose(composeStructuredQuestion(lattice, library))
    } catch (e: ComposeRefused) {
        log.info("γ fired but compose refused: {}", e.message)
        FastPathVerdict.FallThrough(FallThroughReason.COMPOSE_REFUSED, e.message ?: "")
    }
}

/**
 * RV-P5.3 T2 — **the selection stub.**
 *
 * Delegation is RV-P6. Until it lands, everything the fast path does not take reaches a stub
 * that refuses by name, so the H4 posture is honest *before* P6 rather than after it: a Golem
 * that silently did nothing here would look identical to one that had no plugins, and the
 * whole point of H4 is the difference between "I cannot" and "I do not understand".
 *
 * The stub records that it was entered. T6 asserts the H1 fast path never enters it, which is
 * a property of the graph — and a property you can only test if the stub can be observed.
 */
class SelectionStub {
    var entries: Int = 0
        private set

    fun refuse(
        reason: FallThroughReason,
        detail: String,
        gaps: List<GapRecord>,
        library: LayeredSkillLibrary,
    ): RefusalWithGaps {
        entries++
        log.info("selection stub entered ({}): {}", reason, detail)
        return RefusalWithGaps(
            // NO_CAPABLE_PLUGIN even when the reason is GAPS_OPEN, and deliberately: from the
            // user's side the sentence is the same one — "there is nothing here that can take
            // this" — and RV-P6 is what turns it into a real dispatch decision. The typed
            // `reason` below is where the difference survives for an operator.
            code = RefusalCode.NO_CAPABLE_PLUGIN,
            fallThrough = reason,
            explanation = detail,
            gaps = gaps,
            // What it CAN do, when it can do anything. Structured, not apology prose —
            // "I cannot investigate causes" is a capability statement; an apology is not one.
            composableResidue = library.known(),
        )
    }
}

enum class RefusalCode { NO_CAPABLE_PLUGIN, UNRESOLVED_GAPS }

/**
 * The honest refusal (H4). *"I understood you completely; I cannot do this."* The gaps ride
 * along, which is what makes it a refusal rather than a failure.
 */
data class RefusalWithGaps(
    val code: RefusalCode,
    val fallThrough: FallThroughReason,
    val explanation: String,
    val gaps: List<GapRecord> = emptyList(),
    val composableResidue: List<String> = emptyList(),
)

/**
 * contracts §6's answer envelope: `{content, formatting_directives?, provenance,
 * gaps_carried[]}`.
 *
 * `provenance` carries the RV-39 layer tuple + S-1 engine identity, which is the delta P5.1
 * T2(d) recorded as owed to this task: kantheon's `common/v1.BlockProvenance` carries neither,
 * so there was no surface to preserve it onto. It is preserved here, in the Golem's own
 * envelope type.
 *
 * ⚑ **The `common/v1` delta is still owed, and it is now specified rather than deferred.**
 * `BlockProvenance` needs `{lexicon_artifact_hash, member_index_versions, overlay_version?,
 * engine_versions[]}` — additive, no field renumbering — before an RV answer can render its
 * provenance in Iris rather than only carry it in-process. It is not built here because
 * `envelope/v1` is a constellation-wide contract and P5.3 is a Golem task list; changing it
 * belongs with the surface that will render it (P7 / the GC contract), not with the first
 * producer. Recorded as a carry, with the shape decided so the next list has nothing to
 * re-derive.
 */
data class AnswerEnvelope(
    val content: String,
    val formattingDirectives: Map<String, String> = emptyMap(),
    val provenance: ResolutionProvenance? = null,
    /** RV-19 — gaps the answer CARRIED rather than blocked on (DEGRADED / IGNORED notes). */
    val gapsCarried: List<GapRecord> = emptyList(),
    /** Ops whose `requires:` could not be satisfied — "why the comparison is missing". */
    val inapplicableOperators: List<String> = emptyList(),
)
