package org.tatrman.kantheon.golem.resolution

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.resolution.compose.AnswerEnvelope
import org.tatrman.kantheon.golem.resolution.compose.FallThroughReason
import org.tatrman.kantheon.golem.resolution.compose.FastPathVerdict
import org.tatrman.kantheon.golem.resolution.compose.RefusalWithGaps
import org.tatrman.kantheon.golem.resolution.compose.SelectionStub
import org.tatrman.kantheon.golem.resolution.compose.StructuredQuestion
import org.tatrman.kantheon.golem.resolution.compose.fastPathVerdict
import org.tatrman.kantheon.golem.resolution.feedback.FeedbackSink
import org.tatrman.kantheon.golem.resolution.feedback.feedbackFor
import org.tatrman.kantheon.golem.resolution.hitl.Ask
import org.tatrman.kantheon.golem.resolution.hitl.Pin
import org.tatrman.kantheon.golem.resolution.hitl.SignedOption
import org.tatrman.kantheon.golem.resolution.hitl.SnapshotStore
import org.tatrman.kantheon.golem.resolution.hitl.TurnSnapshot
import org.tatrman.kantheon.golem.resolution.hitl.buildAsk
import org.tatrman.kantheon.golem.resolution.hitl.chooseAsk
import org.tatrman.kantheon.golem.resolution.hitl.loadSnapshot
import org.tatrman.kantheon.golem.resolution.intent.TurnIntent
import org.tatrman.kantheon.golem.resolution.intent.classifyTurnIntent
import org.tatrman.kantheon.golem.resolution.ladder.Budgets
import org.tatrman.kantheon.golem.resolution.ladder.GateCall
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.LadderState
import org.tatrman.kantheon.golem.resolution.ladder.Rung
import org.tatrman.kantheon.golem.resolution.ladder.Verdict
import org.tatrman.kantheon.golem.resolution.ladder.carryableGaps
import org.tatrman.kantheon.golem.resolution.ladder.runLadderLoop
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.kantheon.themis.v1.Themis
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.golem.resolution.ResolutionTurn")

// RV-P5.3 T2/T4 — the turn, from a lattice to one of three ends. The graph nodes in
// `graph/GolemGraph.kt` are thin wrappers over this; the orchestration lives here so it can
// be driven from a test without standing up an AIAgent (the repo's step-function idiom).

/** What a resolution turn can END as. Three outcomes, and none of them is an error. */
sealed interface TurnEnd {
    data class Answered(
        val envelope: AnswerEnvelope,
        val question: StructuredQuestion,
        val ladder: LadderState,
        val llmInvocations: Int,
        val asks: Int,
    ) : TurnEnd

    data class Paused(
        val ask: Ask,
        val ladder: LadderState,
    ) : TurnEnd

    data class Refused(
        val refusal: RefusalWithGaps,
        val ladder: LadderState,
    ) : TurnEnd

    /**
     * γ did not fire. **Not an end** — an instruction to enter `selection`, which is the node
     * that turns it into a [Refused].
     *
     * It exists because the stub COUNTS its entries and T6 asserts the count: if `fastPath`
     * refused directly and then handed the turn to `selection`, one fall-through would count
     * as two, and the "H1 never entered selection" assertion would be measuring a number
     * nobody could reason about.
     */
    data class FellThrough(
        val reason: FallThroughReason,
        val detail: String,
        val ladder: LadderState,
    ) : TurnEnd

    /**
     * No lattice — the core is unwired or the turn degraded. **The legacy chain runs**
     * (`resolveSelection → composePlan → gatePlan → execute`), untouched. This is the
     * "additive, inert unless wired" posture P5.1 established, carried through the fast path.
     */
    data class NoResolution(
        val degrade: CoreDegrade?,
    ) : TurnEnd
}

/** What the door was asked, and what came back. The door itself is [QueryDoor]. */
data class DoorAnswer(
    val content: String,
    val rowCount: Long = 0,
)

/**
 * The query door. An interface, because the composed question is an **entity-level** query
 * and the thing that turns it into a physical plan lives on the server — the Golem's job ends
 * at "here is the question, over modeled entities".
 *
 * ⚑ Deliberately NOT `execution.QueryClient`: that edge takes `(source, sourceLanguage,
 * paramsJson)` — a pattern or SQL — and a [StructuredQuestion] is neither. Wiring the two
 * needs the entity-level query surface on `query-mcp`, which is a server-side contract this
 * task list does not own. The seam is here, with a fake behind it in tests, so P5.4/P6 have
 * one place to attach the real door rather than a compose step that dead-ends.
 */
fun interface QueryDoor {
    suspend fun ask(
        question: StructuredQuestion,
        bearer: String?,
    ): DoorAnswer
}

/** Everything the resolution path needs. Assembled once at boot; captured by the nodes. */
data class ResolutionDeps(
    val ladder: LadderConfig,
    val rungs: Map<String, Rung> = emptyMap(),
    val gate: GateCall,
    val library: LayeredSkillLibrary = LayeredSkillLibrary.EMPTY,
    val door: QueryDoor? = null,
    val snapshots: SnapshotStore,
    val feedback: FeedbackSink,
    val selection: SelectionStub = SelectionStub(),
    val estateId: String = "",
    val otel: OpenTelemetry? = null,
    val clockNanos: () -> Long = System::nanoTime,
    val now: () -> Instant = Instant::now,
    val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * ⛑ **Call this at wiring time.** A config that admits a rung the deps do not implement is
     * a misconfiguration, and dispatch already refuses it — but it refuses *mid-turn*, on
     * whichever user's question first produced a gap of the right kind. That is a startup
     * error arriving as a runtime one: it takes a specific gap kind, a specific profile and a
     * specific budget state to reach, so it survives every smoke test and then fails in
     * production on an ordinary question.
     *
     * Found the honest way, at T6: H5's recorded lattice carries a `G1_UNBOUND`, the shipped
     * config admits `lookup` and `local` for G1, and a deps object with no rungs blew up
     * inside the loop rather than at construction.
     *
     * Returns the missing rung names — empty is the healthy answer — so a caller can decide
     * between refusing to boot and logging loudly. `emulated` is exempt: it is deliberately
     * config-legal and unimplemented until RV-P8.
     */
    fun unimplementedRungs(profileName: String): List<String> =
        ladder
            .eligibleRungs(GapKind.entries.filter { it != GapKind.UNRECOGNIZED }, profileName)
            .filter { it != "emulated" && it !in rungs }
}

/** The per-turn facts the resolution path reads and the graph state carries. */
data class TurnFacts(
    val question: String,
    val conversationId: String,
    val callerSubject: String,
    val tenantId: String,
    val locale: String,
    val profileName: String,
    val bearer: String? = null,
    val priorIntent: Themis.Resolution? = null,
    /** Options the CORE signed, lifted off its `AwaitingClarification`. Never invented here. */
    val signedOptions: List<SignedOption> = emptyList(),
    val resumeToken: String = "",
)

/**
 * What `assessGaps` produces: where the ladder stopped, and the intent the exits route on.
 * A value rather than four graph-state fields, so the three exit nodes take one argument and
 * a test can build the input by hand.
 */
data class AssessedGaps(
    val verdict: Verdict,
    val ladder: LadderState,
    val intent: TurnIntent,
    val rounds: Int,
)

/**
 * **`assessGaps`** — RV-11's loop, and the turn's rejoin point on resume.
 *
 * The order is the design's, not an implementation detail: the ladder runs FIRST and the γ
 * predicate reads its result, because "gap-free" means gap-free *after* the Golem has tried
 * to close the gaps. A fast path that fired on the core's own output would skip the loop
 * entirely on every question the core happened to resolve, and never learn that it could have.
 */
suspend fun assessGapsStep(
    lattice: ResolutionState,
    facts: TurnFacts,
    deps: ResolutionDeps,
    resumed: LadderState? = null,
): AssessedGaps {
    val start = resumed ?: LadderState(lattice = lattice, gaps = lattice.gapsList.toList())
    val budgets =
        Budgets(
            profile = deps.ladder.profile(facts.profileName),
            turnStartedAtNanos = deps.clockNanos(),
            clockNanos = deps.clockNanos,
        )
    val outcome =
        runLadderLoop(
            initial = start,
            config = deps.ladder,
            profileName = facts.profileName,
            budgets = budgets,
            rungs = deps.rungs,
            gate = deps.gate,
            otel = deps.otel,
        )
    return AssessedGaps(
        verdict = outcome.verdict,
        ladder = outcome.state,
        intent = classifyTurnIntent(facts.priorIntent, outcome.state.lattice),
        rounds = outcome.rounds,
    )
}

/** The whole path, composed. Used by resume and by the unit tests; the graph runs the nodes. */
suspend fun runResolutionTurn(
    lattice: ResolutionState?,
    facts: TurnFacts,
    deps: ResolutionDeps,
    resumed: LadderState? = null,
): TurnEnd {
    if (lattice == null) return TurnEnd.NoResolution(null)
    val assessed = assessGapsStep(lattice, facts, deps, resumed)
    val end =
        when (assessed.verdict) {
            Verdict.EMIT -> fastPathStep(assessed, facts, deps)
            Verdict.ASK -> askStep(assessed, facts, deps)
            // A ladder REFUSE is `strict` with a blocking gap left over. It is a refusal
            // about GAPS, not about capability — the typed `fallThrough` keeps the two
            // distinguishable even though the user-facing code is the same one.
            Verdict.REFUSE ->
                TurnEnd.FellThrough(
                    FallThroughReason.GAPS_OPEN,
                    "the ladder exhausted and the profile is strict",
                    assessed.ladder,
                )
            // `assess` never returns CLIMB to a caller — the loop exits on anything else —
            // but an exhaustive `when` beats a branch that silently drops a state.
            Verdict.CLIMB -> error("runLadderLoop returned CLIMB, which it cannot: ${assessed.rounds} rounds")
        }
    // The composite runs `selection` itself; the graph has a node for it. Either way the stub
    // is entered exactly once per fall-through.
    return if (end is TurnEnd.FellThrough) selectionStep(end, deps) else end
}

/** `fastPath` as the graph runs it — the γ node. Never refuses; it FALLS THROUGH. */
suspend fun fastPathStep(
    assessed: AssessedGaps,
    facts: TurnFacts,
    deps: ResolutionDeps,
): TurnEnd = emitOrFallThrough(assessed.intent, assessed.ladder, facts, deps)

/** `askGap` as the graph runs it. */
fun askStep(
    assessed: AssessedGaps,
    facts: TurnFacts,
    deps: ResolutionDeps,
): TurnEnd = ask(assessed.ladder, facts, deps)

/** `selection` as the graph runs it — the ONE place a fall-through becomes a refusal. */
fun selectionStep(
    fell: TurnEnd.FellThrough,
    deps: ResolutionDeps,
): TurnEnd.Refused =
    TurnEnd.Refused(
        deps.selection.refuse(fell.reason, fell.detail, fell.ladder.gaps, deps.library),
        fell.ladder,
    )

private suspend fun emitOrFallThrough(
    intent: TurnIntent,
    ladder: LadderState,
    facts: TurnFacts,
    deps: ResolutionDeps,
): TurnEnd =
    when (val verdict = fastPathVerdict(intent, ladder.lattice, ladder.gaps, deps.library, deps.ladder)) {
        is FastPathVerdict.FallThrough -> TurnEnd.FellThrough(verdict.reason, verdict.detail, ladder)

        is FastPathVerdict.Compose -> {
            val door = deps.door
            val answer =
                if (door == null) {
                    // No door wired: the composition is still the deliverable, and saying so
                    // beats fabricating content. Same posture as P5.1's unwired core.
                    log.info("fast path composed but no query door is wired — returning the composition only")
                    DoorAnswer(content = "")
                } else {
                    door.ask(verdict.question, facts.bearer)
                }
            TurnEnd.Answered(
                envelope =
                    AnswerEnvelope(
                        content = answer.content,
                        formattingDirectives = verdict.question.formattingDirectives,
                        provenance = ResolutionProvenance.from(ladder.lattice),
                        // RV-19: what the answer CARRIED rather than blocked on.
                        gapsCarried = carryableGaps(ladder.gaps, deps.ladder),
                        inapplicableOperators = verdict.question.inapplicableOperators,
                    ),
                question = verdict.question,
                ladder = ladder,
                llmInvocations = ladder.llmInvocations,
                asks = ladder.hitlRounds,
            )
        }
    }

/**
 * Emit the ask, and **spend the pool as it goes out**.
 *
 * ⚑ The increment is on the snapshot, not on a live counter, and that is the double-spend
 * defence: a replayed resume reads a state that has already paid. `hitlRounds` is ONE pool —
 * self-answer today, plugin asks at P6 — so a future plugin ask decrements the same number
 * rather than opening a second budget beside it (RV-17).
 */
private fun ask(
    ladder: LadderState,
    facts: TurnFacts,
    deps: ResolutionDeps,
): TurnEnd {
    val gap =
        chooseAsk(ladder.gaps, deps.ladder)
            ?: return TurnEnd.FellThrough(
                FallThroughReason.GAPS_OPEN,
                "gaps are askable by policy but none is load-bearing",
                ladder,
            )

    val spent = ladder.copy(hitlRounds = ladder.hitlRounds + 1)
    val snapshotId = deps.newId()
    val ask =
        buildAsk(
            gap = gap,
            lattice = spent.lattice,
            signedOptions = facts.signedOptions,
            resumeToken = facts.resumeToken,
            snapshotId = snapshotId,
            locale = facts.locale,
        )
    deps.snapshots.put(
        TurnSnapshot(
            id = snapshotId,
            conversationId = facts.conversationId,
            callerSubject = facts.callerSubject,
            tenantId = facts.tenantId,
            question = facts.question,
            locale = facts.locale,
            ladder = spent,
            lattice = spent.lattice,
            signedOptions = facts.signedOptions,
            resumeToken = facts.resumeToken,
            askedGapSpanText = gap.span.text,
            snapshotHashes = snapshotHashes(spent.lattice),
        ),
    )
    return TurnEnd.Paused(ask, spent)
}

/** The RV-39 layer tuple, flattened for the feedback event's `snapshot_hashes`. */
private fun snapshotHashes(lattice: ResolutionState): Map<String, String> =
    buildMap {
        val v = lattice.lexiconVersions
        if (v.lexiconArtifactHash.isNotBlank()) put("lexicon_artifact_hash", v.lexiconArtifactHash)
        if (v.hasOverlayVersion()) put("overlay_version", v.overlayVersion)
        v.memberIndexVersionsMap.forEach { (category, version) -> put("member_index:$category", version) }
    }

/**
 * **Resume — rejoins at `assessGaps`** (RV-11), never at the top of the graph.
 *
 * Three properties, each a bug if it slips:
 *
 *  1. **Resume from the SNAPSHOT, never from live state.** At-least-once delivery is the
 *     norm, so the same resume WILL arrive twice; reading immutable bytes each time is what
 *     makes the second delivery harmless.
 *  2. **The same OBO identity that asked** — checked in [loadSnapshot].
 *  3. **The ask budget rides the snapshot**, so a replayed resume cannot buy a second
 *     question: the stored state already counts it.
 *
 * The feedback event is emitted HERE and only here, because the outcome is the point of the
 * event — an ask nobody answered has nothing to teach.
 */
suspend fun resumeResolutionTurn(
    snapshotId: String,
    pin: Pin,
    callerSubject: String,
    facts: TurnFacts,
    deps: ResolutionDeps,
): TurnEnd {
    val snapshot = loadSnapshot(deps.snapshots, snapshotId, callerSubject)
    val gap =
        snapshot.ladder.gaps.firstOrNull { it.span.text == snapshot.askedGapSpanText }
            ?: snapshot.ladder.gaps.first()

    deps.feedback.emit(
        feedbackFor(
            ask =
                Ask(
                    question = "",
                    gap = gap,
                    options = snapshot.signedOptions,
                    resumeToken = snapshot.resumeToken,
                    snapshotId = snapshot.id,
                ),
            pin = pin,
            estateId = deps.estateId,
            userId = snapshot.callerSubject,
            conversationId = snapshot.conversationId,
            questionText = snapshot.question,
            snapshotHashes = snapshot.snapshotHashes,
            now = deps.now(),
            newId = deps.newId,
        ),
    )

    val resumedLadder =
        when (pin) {
            // A pinned choice re-enters ONLY via `resolve.gate`, as a user-pick hypothesis.
            // The Golem does not bind it — the same fence every rung is behind (RV-7), and
            // the reason a pin is worth signing at all.
            is Pin.Choice -> gateUserPick(snapshot.ladder, gap, pin, snapshot.signedOptions, deps)
            // Unsigned: free text re-resolves through `callResolutionCore`. That call belongs
            // to the graph's own node, so this hands the turn back with the gap still open and
            // the text recorded; the node re-enters `resolve.bind` with it.
            is Pin.FreeText -> snapshot.ladder
            Pin.NoneOfThese -> closeAsUserConfirmedUnknown(snapshot.ladder, gap)
        }

    return runResolutionTurn(
        lattice = resumedLadder.lattice,
        facts =
            facts.copy(
                question = snapshot.question,
                conversationId = snapshot.conversationId,
                callerSubject = snapshot.callerSubject,
                locale = snapshot.locale,
                signedOptions = snapshot.signedOptions,
                resumeToken = snapshot.resumeToken,
            ),
        deps = deps,
        resumed = resumedLadder,
    )
}

private suspend fun gateUserPick(
    ladder: LadderState,
    gap: GapRecord,
    pin: Pin.Choice,
    options: List<SignedOption>,
    deps: ResolutionDeps,
): LadderState {
    val option = options.firstOrNull { it.id == pin.optionId }
    if (option == null || option.ref.isBlank()) {
        log.warn("pin '{}' names no signed option — the gap stays open", pin.optionId)
        return ladder
    }
    val hypothesis =
        Hypothesis
            .newBuilder()
            .setSpan(gap.span)
            .setRef(option.ref)
            // Not a rung. The user is not a proposer in the ladder's sense, and labelling this
            // `local` would make an LLM's guess and a person's answer indistinguishable in the
            // rung log — which is the one place that distinction is worth money.
            .setProposingRung("user")
            .build()
    val result = deps.gate.gate(ladder.lattice, listOf(hypothesis))
    return ladder.copy(
        gaps = result.updatedGaps.ifEmpty { ladder.gaps },
        gatedBindings = ladder.gatedBindings + result.gatedBindings,
        rungLog = ladder.rungLog + listOfNotNull(result.rungLogEntry),
    )
}

/**
 * RV-15 — "none of these" is a real answer. The gap closes as `USER_CONFIRMED_UNKNOWN`, which
 * is not the same as resolved and not the same as still-open: the turn may proceed and carry
 * it, and nothing will ask about it again.
 */
private fun closeAsUserConfirmedUnknown(
    ladder: LadderState,
    gap: GapRecord,
): LadderState =
    ladder.copy(
        gaps =
            ladder.gaps.map {
                if (it.span == gap.span && it.kind == gap.kind) {
                    it.toBuilder().setDisposition(Disposition.DISPOSITION_USER_CONFIRMED_UNKNOWN).build()
                } else {
                    it
                }
            },
    )
