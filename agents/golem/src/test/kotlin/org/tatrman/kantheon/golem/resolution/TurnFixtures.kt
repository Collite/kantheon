package org.tatrman.kantheon.golem.resolution

import org.tatrman.kantheon.golem.resolution.compose.SelectionStub
import org.tatrman.kantheon.golem.resolution.feedback.RecordingFeedbackSink
import org.tatrman.kantheon.golem.resolution.hitl.InMemorySnapshotStore
import org.tatrman.kantheon.golem.resolution.hitl.SnapshotStore
import org.tatrman.kantheon.golem.resolution.ladder.GateCall
import org.tatrman.kantheon.golem.resolution.ladder.GateResult
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.LookupRung
import org.tatrman.kantheon.golem.resolution.ladder.Rung
import org.tatrman.kantheon.golem.resolution.ladder.ScriptedGate
import org.tatrman.kantheon.golem.resolution.ladder.ScriptedRung
import org.tatrman.kantheon.golem.resolution.ladder.gateResult
import org.tatrman.kantheon.golem.resolution.skills.LayeredSkillLibrary
import org.tatrman.kantheon.golem.resolution.skills.SkillLayer
import org.tatrman.kantheon.golem.resolution.skills.fixtureJson
import org.tatrman.kantheon.themis.v1.Themis
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * RV-P5.3 — deps for a resolution turn, wired for a test.
 *
 * Every non-determinism is injectable: the clock, the id generator, the gate, the door. A
 * turn whose snapshot id changes per run cannot be asserted on, and a turn whose budget can
 * only be observed by waiting cannot be tested at all.
 */
fun testDeps(
    ladder: LadderConfig = LadderConfig.loadDefault(),
    // The shipped config admits `lookup` and `local` for a G1, so a deps object with no
    // rungs throws mid-loop (see `unimplementedRungs`). These are the honest defaults: the
    // deterministic rung, and an LLM rung that proposes nothing — a real and common answer.
    rungs: Map<String, Rung> = mapOf("lookup" to LookupRung(), "local" to ScriptedRung()),
    gate: GateCall = ScriptedGate(gateResult()),
    library: LayeredSkillLibrary = LayeredSkillLibrary(listOf(SkillLayer.fromJson(fixtureJson()))),
    door: QueryDoor? = QueryDoor { q, _ -> DoorAnswer(content = "rows for ${q.measures + q.subjects}", rowCount = 3) },
    snapshots: SnapshotStore = InMemorySnapshotStore(),
    feedback: RecordingFeedbackSink = RecordingFeedbackSink(),
    selection: SelectionStub = SelectionStub(),
    nanos: () -> Long = { 0L },
): ResolutionDeps =
    ResolutionDeps(
        ladder = ladder,
        rungs = rungs,
        gate = gate,
        library = library,
        door = door,
        snapshots = snapshots,
        feedback = feedback,
        selection = selection,
        estateId = "test-estate",
        clockNanos = nanos,
        now = { Instant.parse("2026-08-06T12:00:00Z") },
        newId = SequentialIds(),
    )

/** Ids a test can predict. `Math.random()` in a fixture is a flaky test waiting to happen. */
class SequentialIds(
    private val prefix: String = "id",
) : () -> String {
    private val n = AtomicInteger()

    override fun invoke(): String = "$prefix-${n.incrementAndGet()}"
}

fun facts(
    question: String = "Zobraz tržby podle prodejen",
    intent: Themis.IntentKind? = Themis.IntentKind.PROCEDURAL,
    profile: String = "CHAT_QUICK",
    conversationId: String = "conv-1",
    subject: String = "user-1",
    signedOptions: List<org.tatrman.kantheon.golem.resolution.hitl.SignedOption> = emptyList(),
    resumeToken: String = "",
): TurnFacts =
    TurnFacts(
        question = question,
        conversationId = conversationId,
        callerSubject = subject,
        tenantId = "t1",
        locale = "cs",
        profileName = profile,
        priorIntent =
            intent?.let {
                Themis.Resolution
                    .newBuilder()
                    .setIntentKind(it)
                    .build()
            },
        signedOptions = signedOptions,
        resumeToken = resumeToken,
    )

/** A gate that binds nothing and closes nothing — the "no rung helped" case. */
fun inertGate(): GateCall = GateCall { _, _ -> GateResult(emptyList(), emptyList(), null, emptyList()) }
