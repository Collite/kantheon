package org.tatrman.kantheon.golem.resolution.ladder

import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding

/**
 * RV-P5.2 T1 — synthetic lattices. Deliberately hand-built rather than taken from the
 * recorded goldens: the loop's semantics are about gap kinds, roles and budgets, and a
 * table-driven test wants to vary exactly one of those at a time. The goldens drive the
 * component pass in T6, where realism is the point.
 */
fun span(
    text: String,
    start: Int = 0,
): Span =
    Span
        .newBuilder()
        .setStart(start)
        .setEnd(start + text.length)
        .setText(text)
        .build()

fun gap(
    kind: GapKind,
    text: String,
    start: Int = 0,
    roles: List<FrameRole> = emptyList(),
    mentionId: String = "",
    valueId: String = "",
    disposition: Disposition = Disposition.DISPOSITION_UNRESOLVED,
    tried: List<Hypothesis> = emptyList(),
): GapRecord =
    GapRecord
        .newBuilder()
        .setKind(kind)
        .setSpan(span(text, start))
        .addAllFrameRoles(roles)
        .setMentionId(mentionId)
        .setValueId(valueId)
        .setDisposition(disposition)
        .addAllHypothesesTried(tried)
        .build()

fun mention(
    id: String,
    text: String,
    start: Int = 0,
    refs: List<String> = emptyList(),
): Mention =
    Mention
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setLemma(text)
        .addAllBindings(refs.map { binding(it) })
        .build()

fun value(
    id: String,
    text: String,
    start: Int = 0,
    anchorMentionId: String = "",
): ValueFinding =
    ValueFinding
        .newBuilder()
        .setId(id)
        .setSpan(span(text, start))
        .setAnchorMentionId(anchorMentionId)
        .build()

/**
 * Only tests build these — production code never constructs a `Binding` (RV-7), which
 * `SingleBinderFenceSpec` enforces by grepping `src/main`.
 */
fun binding(ref: String): Binding =
    Binding
        .newBuilder()
        .setRef(ref)
        .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
        .setEvidenceClass(EvidenceClass.EVIDENCE_CLASS_EXACT)
        .setInClassScore(1.0)
        .build()

fun lattice(
    mentions: List<Mention> = emptyList(),
    values: List<ValueFinding> = emptyList(),
    gaps: List<GapRecord> = emptyList(),
): ResolutionState =
    ResolutionState
        .newBuilder()
        .addAllMentions(mentions)
        .addAllValues(values)
        .addAllGaps(gaps)
        .addRungLog(
            RungLogEntry
                .newBuilder()
                .setRound(0)
                .setRung("core")
                .setAction("annotate"),
        ).build()

fun hypothesis(
    text: String,
    ref: String,
    rung: String = "local",
    start: Int = 0,
): Hypothesis =
    Hypothesis
        .newBuilder()
        .setSpan(span(text, start))
        .setRef(ref)
        .setProposingRung(rung)
        .build()

/** A clock a test drives by hand — a budget that can only be observed by waiting is untested. */
class FakeClock(
    var nanos: Long = 0,
) {
    fun advanceMs(ms: Long) {
        nanos += ms * 1_000_000
    }

    fun reader(): () -> Long = { nanos }
}

/** The config kantheon ships, loaded once for the table-driven tests. */
fun shippedLadder(): LadderConfig = LadderConfig.loadDefault()

fun budgetsFor(
    config: LadderConfig,
    profile: String,
    clock: FakeClock = FakeClock(),
): Budgets = Budgets(config.profile(profile), turnStartedAtNanos = clock.nanos, clockNanos = clock.reader())

/** A rung that proposes exactly what it is told to, and records that it ran. */
class ScriptedRung(
    private val proposals: MutableList<RungProposal>,
) : Rung {
    val calls = mutableListOf<List<GapRecord>>()

    constructor(vararg proposals: RungProposal) : this(proposals.toMutableList())

    override suspend fun propose(
        state: LadderState,
        gaps: List<GapRecord>,
        config: LadderConfig,
    ): RungProposal {
        calls += gaps
        return if (proposals.isEmpty()) RungProposal(emptyList()) else proposals.removeAt(0)
    }
}

/** A gate a test scripts turn by turn. Counts calls so idempotent-retry can be asserted. */
class ScriptedGate(
    private val results: MutableList<GateResult>,
) : GateCall {
    var calls = 0
        private set
    val hypothesesSeen = mutableListOf<List<Hypothesis>>()

    constructor(vararg results: GateResult) : this(results.toMutableList())

    override suspend fun gate(
        lattice: ResolutionState,
        hypotheses: List<Hypothesis>,
    ): GateResult {
        calls++
        hypothesesSeen += hypotheses
        return if (results.size > 1) results.removeAt(0) else results.first()
    }
}

fun gateResult(
    bindings: List<Binding> = emptyList(),
    updatedGaps: List<GapRecord> = emptyList(),
    outcomes: List<HypothesisVerdict> = emptyList(),
    rungLogEntry: RungLogEntry? = null,
): GateResult = GateResult(bindings, updatedGaps, rungLogEntry, outcomes)
