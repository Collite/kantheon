package org.tatrman.kantheon.golem.conformance

import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.golem.graph.GolemGraphDeps
import org.tatrman.kantheon.golem.graph.callResolutionCoreNode
import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.golem.graph.walkResolutionNodes
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.ResolutionDeps
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.TurnFacts
import org.tatrman.kantheon.golem.resolution.compose.SelectionStub
import org.tatrman.kantheon.golem.resolution.feedback.RecordingFeedbackSink
import org.tatrman.kantheon.golem.resolution.hitl.Pin
import org.tatrman.kantheon.golem.resolution.ladder.GateResult
import org.tatrman.kantheon.golem.resolution.ladder.LadderConfig
import org.tatrman.kantheon.golem.resolution.ladder.LookupRung
import org.tatrman.kantheon.golem.resolution.ladder.Rung
import org.tatrman.kantheon.golem.resolution.ladder.ScriptedRung
import org.tatrman.kantheon.golem.resolution.resumeResolutionTurn
import org.tatrman.kantheon.golem.resolution.testDeps
import org.tatrman.kantheon.golem.v1.Caller
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState

/**
 * RV-P5.4 T2 — drive a shared conversation fixture through the Kotlin Golem.
 *
 * The Python twin is `services/golem-py/tests/conversation_runner.py`, and the two are held to
 * the same discipline: **assert what the fixture SAYS**, never what this shell happens to
 * produce. A shell-specific check would pass in one language and be unwritable in the other,
 * which is exactly how "one corpus, N shells" stops being true.
 *
 * ⚑ **T1 Ruling — the shared corpus runs the ZERO-RUNG config, in both shells.**
 *
 * kantheon deliberately *ships* a different ladder file: `golem-ladder.yaml` is the
 * internal-full table (contracts §3 as written), not the open Golem's zero-rung default. That
 * difference is real and stays. But the shared fixtures assert NUMBERS that a ladder changes —
 * `h1prime` and `h2` both claim `llm_invocations: 0`, and under the internal-full table a
 * `local` rung is eligible for their gaps and spends one whether or not it proposes anything.
 *
 * So the config is part of the **fixture's premise**, not part of the shell. Running the shared
 * corpus under a different ladder would not be the Kotlin Golem failing conformance; it would
 * be the two shells answering different questions and calling the agreement a corpus. The
 * internal-full table is exercised where climbing is the point — `LadderConversationSpec`
 * (T3) — and `the suite runs the vendored zero-rung file, unmodified` is asserted here, which
 * is the Kotlin mirror of golem-py's own "no fixture may quietly enable a rung".
 */
class ConversationRun(
    val id: String,
    val fixture: JsonObject,
) {
    /** One entry per NON-gate turn, in order — the index `byte_identical_to_turn` uses. */
    val ends: MutableList<TurnEnd> = mutableListOf()

    /** One entry per bare `resolve.gate:v1` turn, in order. */
    val gateResults: MutableList<GateResult> = mutableListOf()

    /** The hypotheses the GOLEM sent, per non-gate turn, aligned with [ends]. */
    val sentHypotheses: MutableList<List<Hypothesis>> = mutableListOf()

    val selection: SelectionStub = SelectionStub()
    val feedback: RecordingFeedbackSink = RecordingFeedbackSink()

    /** `core_calls_total` — resolve.bind calls made by the CURRENT turn's client, as golem-py counts them. */
    var coreCalls: Int = 0
        private set

    private var client: RecordedResolutionCore.RecordingClient? = null
    private var gate: RecordedGate = RecordedGate()
    private var deps: ResolutionDeps? = null
    private var facts: TurnFacts? = null
    private var snapshotId: String = ""

    /** The lattice the previous non-gate turn ended with — what a bare gate turn folds into. */
    var lattice: ResolutionState? = null
        private set

    suspend fun run(): ConversationRun {
        fixture.arr("turns").forEach { element ->
            val turn = element.jsonObject
            when (val tool = turn.strOr("tool", "")) {
                "golem.turn:v1" -> turn(turn)
                "golem.resume:v1" -> resume(turn)
                "resolve.gate:v1" -> gateOnly(turn)
                else -> error("$id: unknown turn tool '$tool'")
            }
        }
        return this
    }

    private suspend fun turn(turn: JsonObject) {
        val args = turn.obj("args") ?: error("$id: a golem.turn:v1 needs args")
        val core = turn.obj("core") ?: error("$id: a golem.turn:v1 needs a core block")

        val response = recordedResponse(core)
        val recording = RecordedResolutionCore.RecordingClient(answer = { response })
        client = recording
        gate = recordedGate(turn.obj("gate"))

        val resolution =
            testDeps(
                // See the class KDoc: the SHARED corpus runs the zero-rung file, unmodified.
                ladder = sharedCorpusLadder(),
                rungs = SHARED_CORPUS_RUNGS,
                gate = gate,
                selection = selection,
                feedback = feedback,
            )
        deps = resolution

        val graphDeps =
            GolemGraphDeps(
                // The LEGACY chain's deps. Relaxed mocks and never called: every fixture has a
                // lattice, so the walk never reaches `resolveSelection`. If one of these is
                // ever invoked, that is itself the finding.
                composer = mockk(relaxed = true),
                validator = mockk(relaxed = true),
                miniPlanExecutor = mockk(relaxed = true),
                promptExecutor = mockk(relaxed = true),
                resolutionCore = recording,
                resolution = resolution,
                profileName = { args.strOr("profile", "CHAT_QUICK") },
                // Frozen: a byte-identical replay must not race a clock.
                referenceDatetime = { "2026-08-06T12:00:00Z" },
            )

        val start =
            GolemTurnState(
                request = requestFor(args),
                userId = args.strOr("caller_subject", ""),
                tenantId = "t1",
            )
        val afterCore = callResolutionCoreNode(start, graphDeps)
        val out = walkResolutionNodes(afterCore, graphDeps)

        coreCalls = recording.requests.size
        record(out.turnEnd, out.turnFacts)
    }

    private suspend fun resume(turn: JsonObject) {
        val resolution = requireNotNull(deps) { "$id: a resume must follow a turn" }
        // A resume may bring its own recorded gate — that is how a pin becomes a binding.
        turn.obj("gate")?.let { spec ->
            gate = recordedGate(spec)
            deps = resolution.copy(gate = gate)
        }
        val active = requireNotNull(deps)
        val pinSpec = turn.obj("pin") ?: JsonObject(emptyMap())
        val pin =
            when {
                pinSpec.boolOr("escape", false) -> Pin.NoneOfThese
                !pinSpec.strOr("free_text", "").isEmpty() -> Pin.FreeText(pinSpec.strOr("free_text", ""))
                else -> Pin.Choice(pinSpec.strOr("option_id", ""))
            }

        val before = gate.sent.size
        val end =
            resumeResolutionTurn(
                snapshotId = snapshotId,
                pin = pin,
                callerSubject = turn.strOr("caller_subject", ""),
                facts = requireNotNull(facts) { "$id: a resume must follow a turn" },
                deps = active,
            )
        // A resume that pauses AGAIN mints its own snapshot; an answer or a refusal has none
        // and must not clear the id a replay turn still needs.
        record(end, facts, sent = gate.sent.drop(before).flatten())
    }

    /**
     * A bare re-gate turn: hypotheses in, gated bindings out, folded into the lattice the PRIOR
     * turn produced. Under the zero-rung default nothing is proposed by the shell itself, so the
     * hypotheses arrive from the fixture — the same way golem-py drives it. That the Kotlin
     * shell can also produce them from its own `local` rung is a separate, kantheon-local claim
     * (`LadderConversationSpec`): a rung scripted to emit the fixture's hypothesis and the
     * fixture supplying it are the same experiment, and only one of them keeps the corpus shared.
     */
    private suspend fun gateOnly(turn: JsonObject) {
        val current = requireNotNull(lattice) { "$id: a gate turn must follow a turn" }
        val recorded = recordedGate(turn.obj("gate"))
        val hypotheses = turn.arr("hypotheses").map { hypothesis(it.jsonObject) }
        val result = recorded.gate(current, hypotheses)
        lattice = applyGateResult(current, result)
        gateResults += result
    }

    private fun record(
        end: TurnEnd?,
        turnFacts: TurnFacts?,
        sent: List<Hypothesis> = gate.sent.lastOrNull().orEmpty(),
    ) {
        val resolved = requireNotNull(end) { "$id: a turn produced no TurnEnd — is the resolution core wired?" }
        ends += resolved
        sentHypotheses += sent
        turnFacts?.let { facts = it }
        latticeOf(resolved)?.let { lattice = it }
        (resolved as? TurnEnd.Paused)?.let { snapshotId = it.ask.snapshotId }
    }
}

/** The zero-rung file the shared corpus was authored against, vendored at P5.2. */
internal fun sharedCorpusLadder(): LadderConfig =
    LadderConfig.parse(
        requireNotNull(ConversationFixtures::class.java.getResourceAsStream("/ladder/golem-ladder-open.yaml")) {
            "missing vendored ladder/golem-ladder-open.yaml"
        }.bufferedReader().use { it.readText() },
        "classpath:/ladder/golem-ladder-open.yaml",
    )

/**
 * The rung implementations the shared tier wires. No policy row admits any of them under the
 * zero-rung file, so these are never reached — they exist so that a config change would show up
 * as a *behaviour* difference rather than as a `LadderConfigException` about a missing
 * implementation, which would read like an infrastructure fault rather than a broken premise.
 */
internal val SHARED_CORPUS_RUNGS: Map<String, Rung> = mapOf("lookup" to LookupRung(), "local" to ScriptedRung())

internal fun requestFor(args: JsonObject): GolemRequest =
    GolemRequest
        .newBuilder()
        .setId(args.strOr("turn_id", "t-1"))
        .setGolemId("golem-erp")
        .setQuestion(args.strOr("text", ""))
        .setContext(
            GolemContext
                .newBuilder()
                .setLocale(args.strOr("locale", "cs"))
                .setConversationId(args.strOr("conversation_id", "")),
        ).setCaller(
            Caller
                .newBuilder()
                .setUserId(args.strOr("caller_subject", ""))
                .setTenantId("t1")
                .setCorrelationId(args.strOr("turn_id", "t-1")),
            // ⚑ No `resolved_intent`: the SCHEMA has no key for one, and inventing a Themis
            // verdict here would make the Kotlin shell pass H4 for a reason golem-py cannot
            // have. Every fixture therefore runs on OPERATOR EVIDENCE alone — the same signal
            // the OS Golem uses — which is what makes the outcomes comparable at all.
        ).build()

internal fun latticeOf(end: TurnEnd): ResolutionState? =
    when (end) {
        is TurnEnd.Answered -> end.ladder.lattice
        is TurnEnd.Paused -> end.ladder.lattice
        is TurnEnd.Refused -> end.ladder.lattice
        is TurnEnd.FellThrough -> end.ladder.lattice
        is TurnEnd.NoResolution -> null
    }

suspend fun drive(id: String): ConversationRun = ConversationRun(id, ConversationFixtures.load(id)).run()
