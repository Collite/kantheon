package org.tatrman.kantheon.golem.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.ladder.openGaps
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.ResolutionState
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// RV-P5.4 T2 — the SHARED conformance-conversation corpus, through the Kotlin Golem.
//
// The same five files golem-py runs, unchanged (RV-28: one corpus, one core, N shells). Every
// assertion reads the fixture's own `expect:` block, and the guard below rejects a fixture that
// states a key this runner does not read — SCHEMA.md says in as many words that "the Kotlin
// shell needs its own", after a review found five keys silently ignored across eleven
// occurrences upstream.
//
// (Line comments, not a KDoc: ktlint forbids a KDoc immediately followed by an EOL comment, and
// the `_KEYS` table below has to carry its own note. Same rule hit at P5.2 and P5.3.)

// ⛑ Every key a fixture may state. Kept in the same four groups as golem-py's, and deliberately
// NOT derived from a single flat set: which keys are legal depends on the outcome, and a runner
// that accepted `refusal_reason` on an answer would be reading a fixture it does not understand.
private val TURN_KEYS =
    setOf("outcome", "llm_invocations", "asks", "no_binding_below_threshold", "byte_identical_to_turn")
private val ASK_KEYS = setOf("gap_kind", "asked_span", "min_options", "escape_offered", "snapshot_stored")
private val ANSWER_KEYS =
    setOf(
        "core_calls_total",
        "measures",
        "subjects",
        "operators",
        "inapplicable_operators",
        "member_filters",
        "gaps_carried",
        "gaps_carried_spans",
        "provenance_lexicon_artifact_hash",
        "gated_refs",
        "proposing_rung",
    )
private val REFUSAL_KEYS = setOf("refusal_reason", "min_bindings", "gap_kinds", "composable_residue")
private val GATE_KEYS = setOf("gated_refs", "evidence_classes", "proposing_rung", "gap_kinds")
private val KNOWN_TURN_KEYS = TURN_KEYS + ASK_KEYS + ANSWER_KEYS + REFUSAL_KEYS

private val EXPECTED_SHA256 =
    mapOf(
        "h1-answer" to "e911b4f97bd42cff1be9f442106a21a133bf3af74a8565cafa75dd19c9999810",
        "h1prime-regate" to "845c5fd891c6161dbcb7bb86388303a3ee0658c252f90d87fc2a275b5d935c15",
        "h2-ask-pin-resume" to "b60cdb88d3c33ac5f038118642b84a32270f438e3017ca36d13c4c23adda0354",
        "h4-refusal" to "215928615eff0de91818cf0b9b3dcde7d12d19e84760e8a870c6675912196918",
        "h5-answer-with-gap" to "3b0eb8b31502e2cc881fc284bd4220a84253f654bf80f84d0249cf491eea624f",
    )

private const val UPSTREAM_DIR = "conformance/conversations"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * **Weakest-first**, so `rank < WEAK.rank` is the floor RV-14 states.
 *
 * ⚑ The proto's own numbering will not do: `EVIDENCE_CLASS_UNSPECIFIED = 0` sits *below* EXACT
 * numerically, so a naive `ordinal` comparison would read the weakest class of all as the
 * strongest. golem-py's `rank()` makes the same correction; the two must agree or the shared
 * `no_binding_below_threshold` invariant means different things in the two shells.
 */
private fun EvidenceClass.rank(): Int =
    when (this) {
        EvidenceClass.EVIDENCE_CLASS_EXACT -> 0
        EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS -> 1
        EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS -> 2
        EvidenceClass.EVIDENCE_CLASS_ANCHORED_FUZZY_STRONG -> 3
        EvidenceClass.EVIDENCE_CLASS_UNANCHORED_FUZZY_STRONG -> 4
        EvidenceClass.EVIDENCE_CLASS_WEAK -> 5
        else -> 6 // UNSPECIFIED / UNRECOGNIZED — weaker than WEAK, never bindable
    }

/** Every binding in a turn's lattice — on mentions AND on value attributions. */
private fun bindings(lattice: ResolutionState?): List<Binding> {
    if (lattice == null) return emptyList()
    return lattice.mentionsList.flatMap { it.bindingsList } +
        lattice.valuesList.flatMap { v -> v.attributionsList.filter { it.hasBinding() }.map { it.binding } }
}

private fun boundRefs(lattice: ResolutionState?): List<String> = bindings(lattice).map { it.ref }

/**
 * A turn's output as a canonical string — the `byte_identical_to_turn` comparison.
 *
 * golem-py compares `model_dump_json()`; Kotlin's data classes hold protobuf messages whose
 * `toString()` is not a stable serialisation contract, so the surface is rendered explicitly.
 * That is the stricter choice anyway: it names exactly what a redelivery must reproduce.
 */
private fun render(end: TurnEnd): String =
    when (end) {
        is TurnEnd.Answered ->
            "answered|${end.envelope.content}|${end.question}|${end.envelope.formattingDirectives}|" +
                "${end.envelope.gapsCarried.map { it.kind.name to it.span.text }}|" +
                "${end.envelope.inapplicableOperators}|${end.envelope.provenance}|" +
                "llm=${end.llmInvocations}|asks=${end.asks}|refs=${boundRefs(end.ladder.lattice)}"
        is TurnEnd.Paused ->
            "paused|${end.ask.question}|${end.ask.gap.kind.name}|${end.ask.gap.span.text}|" +
                "${end.ask.options.map { it.id to it.ref }}|escape=${end.ask.escape}"
        is TurnEnd.Refused ->
            "refused|${end.refusal.code}|${end.refusal.fallThrough}|${end.refusal.composableResidue}|" +
                "${end.refusal.gaps.map { it.kind.name }}"
        is TurnEnd.FellThrough -> "fell-through|${end.reason}"
        is TurnEnd.NoResolution -> "no-resolution"
    }

private fun llmInvocations(end: TurnEnd): Int =
    when (end) {
        is TurnEnd.Answered -> end.llmInvocations
        is TurnEnd.Paused -> end.ladder.llmInvocations
        is TurnEnd.Refused -> end.ladder.llmInvocations
        is TurnEnd.FellThrough -> end.ladder.llmInvocations
        is TurnEnd.NoResolution -> 0
    }

private fun asks(end: TurnEnd): Int =
    when (end) {
        is TurnEnd.Answered -> end.asks
        is TurnEnd.Paused -> end.ladder.hitlRounds
        is TurnEnd.Refused -> end.ladder.hitlRounds
        is TurnEnd.FellThrough -> end.ladder.hitlRounds
        is TurnEnd.NoResolution -> 0
    }

private fun latticeFor(end: TurnEnd): ResolutionState? = latticeOf(end)

class ConformanceConversationsSpec :
    StringSpec({

        // -------------------------------------------------------------- the corpus is intact

        "the corpus holds exactly the five heroes" {
            // A suite that silently loses a fixture reports as green.
            ConversationFixtures.IDS shouldContainExactly EXPECTED_SHA256.keys.sorted()
        }

        "every vendored fixture still hashes to what PROVENANCE.md records" {
            EXPECTED_SHA256.forEach { (id, expected) ->
                withClue(id) { sha256(ConversationFixtures.bytes(id)) shouldBe expected }
            }
        }

        "the vendored fixtures match tatrman-server's originals when a sibling checkout is available" {
            val siblingRoot = System.getenv("TATRMAN_SERVER_DIR")
            if (siblingRoot == null) {
                // A skip that prints nothing is how a vendored fixture rots. This is also the
                // one check that can only fail with NO kantheon commit behind it, which is why
                // `eval-nightly.yml` exists (T5).
                println(
                    "SKIPPED cross-repo drift check: set TATRMAN_SERVER_DIR to a tatrman-server " +
                        "checkout to diff agents/golem/src/test/resources/conversations/ against " +
                        "$UPSTREAM_DIR/. The sha256 test above still ran — it proves these files " +
                        "have not changed HERE, not that they still match THERE.",
                )
            } else {
                EXPECTED_SHA256.forEach { (id, expected) ->
                    val upstream = Path.of(siblingRoot, UPSTREAM_DIR, "$id.json")
                    // ⛑ A MISSING original is a drift, not a skip — see the same correction in
                    // `RecordedCoreProvenanceSpec`. An upstream rename left this green while the
                    // Kotlin shell quietly stopped being held to the shared corpus, which is the
                    // one failure `eval-nightly.yml` exists to catch.
                    withClue("no such file at $upstream — the original moved or was deleted upstream") {
                        Files.exists(upstream) shouldBe true
                    }
                    withClue("$id.json drifted from $upstream") {
                        sha256(Files.readAllBytes(upstream)) shouldBe expected
                    }
                }
            }
        }

        // ------------------------------------------------------------------------- the guards

        ConversationFixtures.IDS.forEach { id ->
            "$id states its invariants and names its corpus" {
                val fixture = ConversationFixtures.load(id)
                fixture.strOr("corpus", "") shouldBe "hartland_cz"
                withClue("a fixture with no stated invariant teaches nothing") {
                    fixture.strings("invariants").size shouldBeGreaterThanOrEqual 1
                }
                withClue("a fixture with no turns asserts nothing") {
                    fixture.arr("turns").size shouldBeGreaterThanOrEqual 1
                }
            }

            "$id states no expectation this runner never reads" {
                // The guard SCHEMA.md asks for by name. A shared corpus that asserts less than
                // it says is worse than a smaller one, because the second shell reads the file
                // and believes it.
                ConversationFixtures.load(id).arr("turns").forEachIndexed { position, element ->
                    val turn = element.jsonObject
                    val known = if (turn.strOr("tool", "") == "resolve.gate:v1") GATE_KEYS else KNOWN_TURN_KEYS
                    val unknown = ((turn.obj("expect")?.keys ?: emptySet()) - known).sorted()
                    withClue("$id turn ${position + 1} states unread key(s)") { unknown shouldBe emptyList() }
                }
            }

            "$id copies no lattice — every core block names a golden by id" {
                ConversationFixtures.load(id).arr("turns").forEach { element ->
                    element.jsonObject.obj("core")?.let { core ->
                        withClue("$id: a fixture that inlined a lattice would drift from the core's own suite") {
                            core.str("lattice") shouldNotBe null
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------------ the fixtures

        ConversationFixtures.IDS.forEach { id ->
            "$id drives through the Kotlin Golem" {
                runTest {
                    val run = drive(id)
                    val turns = run.fixture.arr("turns").map { it.jsonObject }
                    turns.filter { it.strOr("tool", "") != "resolve.gate:v1" }.forEachIndexed { index, turn ->
                        assertTurn(id, index, turn.obj("expect") ?: JsonObject(emptyMap()), run)
                    }
                    turns.filter { it.strOr("tool", "") == "resolve.gate:v1" }.forEachIndexed { index, turn ->
                        assertGate(id, index, turn.obj("expect") ?: JsonObject(emptyMap()), run)
                    }
                }
            }
        }

        // ------------------------------------------------------ the config the suite ran under

        "the shared corpus ran the vendored ZERO-RUNG file, unmodified" {
            // golem-py's mirror of this asserts its shipped default; kantheon ships the
            // internal-full table instead, so what is pinned here is the corpus's PREMISE
            // rather than this Golem's default. If this ever needs relaxing, the relaxation is
            // the finding — see the ConversationRun KDoc for why the premise is the config.
            val ladder = sharedCorpusLadder()
            ladder.policy.values.forEach { it.rungs shouldBe emptyList() }
        }
    })

private fun assertTurn(
    id: String,
    index: Int,
    expect: JsonObject,
    run: ConversationRun,
) {
    val where = "$id turn ${index + 1}"
    val end = run.ends[index]
    val outcome = expect.strOr("outcome", "")
    val lattice = latticeFor(end)

    // ---- outcome-independent, because the claims are ----
    expect["llm_invocations"]?.let {
        withClue("$where: llm_invocations") { llmInvocations(end) shouldBe expect.intOr("llm_invocations", -1) }
    }
    expect["asks"]?.let {
        // H4 states `asks: 0` on a REFUSAL and h2 states `asks: 1` on an answer reached through
        // a pause: the count is the TURN's, not the outcome's.
        withClue("$where: asks") { asks(end) shouldBe expect.intOr("asks", -1) }
    }
    if (expect.boolOr("no_binding_below_threshold", false)) {
        bindings(lattice).forEach { binding ->
            withClue("$where: ${binding.ref} bound at ${binding.evidenceClass}") {
                (binding.evidenceClass.rank() < EvidenceClass.EVIDENCE_CLASS_WEAK.rank()) shouldBe true
            }
        }
    }
    expect["byte_identical_to_turn"]?.let {
        // At-least-once delivery is the norm, so a redelivery must produce the same output.
        // The index is 0-based over the NON-GATE turns, which is what `run.ends` holds.
        withClue("$where: a replayed resume must be byte-identical") {
            render(end) shouldBe render(run.ends[expect.intOr("byte_identical_to_turn", 0)])
        }
    }
    expect["proposing_rung"]?.let {
        // RV-7 about the USER's pin: what matters is what we PROPOSED, not what the recorded
        // gate echoed back. A pin's rung is `user`, deliberately outside the four-rung
        // vocabulary, so the ladder's health numbers cannot be made to lie by it.
        val sent = run.sentHypotheses[index]
        withClue("$where: expected a hypothesis carrying a proposing rung") { sent.isNotEmpty() shouldBe true }
        sent.forEach { withClue(where) { it.proposingRung shouldBe expect.strOr("proposing_rung", "") } }
    }

    when (outcome) {
        "ask" -> {
            val paused = end as? TurnEnd.Paused ?: error("$where: expected a pause, got ${render(end)}")
            expect["min_options"]?.let {
                withClue("$where: min_options") {
                    paused.ask.options.size shouldBeGreaterThanOrEqual expect.intOr("min_options", 0)
                }
            }
            expect["gap_kind"]?.let {
                withClue(
                    where,
                ) { paused.ask.gap.kind.name shouldBe expect.strOr("gap_kind", "") }
            }
            expect["asked_span"]?.let {
                withClue(where) {
                    paused.ask.question shouldContain
                        expect.strOr("asked_span", "")
                }
            }
            expect["escape_offered"]?.let {
                withClue(where) { paused.ask.escape.isNotEmpty() shouldBe expect.boolOr("escape_offered", true) }
            }
            expect["snapshot_stored"]?.let {
                withClue(where) { paused.ask.snapshotId.isNotEmpty() shouldBe expect.boolOr("snapshot_stored", true) }
            }
        }

        "refusal" -> {
            val refused = end as? TurnEnd.Refused ?: error("$where: expected a refusal, got ${render(end)}")
            withClue(where) { refused.refusal.code.name shouldBe expect.strOr("refusal_reason", "") }
            expect["min_bindings"]?.let {
                withClue("$where: min_bindings") {
                    bindings(lattice).size shouldBeGreaterThanOrEqual expect.intOr("min_bindings", 0)
                }
            }
            expect["gap_kinds"]?.let {
                withClue(where) {
                    openGaps(refused.refusal.gaps).map { g -> g.kind.name } shouldBe
                        expect.strings("gap_kinds")
                }
            }
            expect["composable_residue"]?.let {
                withClue(where) { refused.refusal.composableResidue shouldBe expect.strings("composable_residue") }
            }
        }

        else -> {
            withClue("$where: outcome") { outcome shouldBe "answer" }
            val answered = end as? TurnEnd.Answered ?: error("$where: expected an answer, got ${render(end)}")
            val q = answered.question
            expect["core_calls_total"]?.let {
                withClue("$where: core_calls_total") { run.coreCalls shouldBe expect.intOr("core_calls_total", -1) }
            }
            expect["measures"]?.let { withClue(where) { q.measures shouldBe expect.strings("measures") } }
            expect["subjects"]?.let { withClue(where) { q.subjects shouldBe expect.strings("subjects") } }
            expect["operators"]?.let { withClue(where) { q.operators shouldBe expect.strings("operators") } }
            expect["inapplicable_operators"]?.let {
                withClue(where) { q.inapplicableOperators shouldBe expect.strings("inapplicable_operators") }
            }
            expect["member_filters"]?.let {
                withClue(where) {
                    q.filters.filter { f -> f.memberRef.isNotBlank() }.map { f -> f.memberRef } shouldBe
                        expect.strings("member_filters")
                }
            }
            expect["gaps_carried"]?.let {
                withClue(where) {
                    answered.envelope.gapsCarried.map { g -> g.kind.name } shouldBe
                        expect.strings("gaps_carried")
                }
            }
            expect["gaps_carried_spans"]?.let {
                withClue(where) {
                    answered.envelope.gapsCarried.map { g -> g.span.text } shouldBe expect.strings("gaps_carried_spans")
                }
            }
            expect["provenance_lexicon_artifact_hash"]?.let {
                withClue(where) {
                    answered.envelope.provenance?.lexiconArtifactHash shouldBe
                        expect.strOr("provenance_lexicon_artifact_hash", "")
                }
            }
            expect["gated_refs"]?.let {
                // The pinned entity must be IN THE LATTICE, not merely in the accounting —
                // this is the assertion that caught `foldGateResult` missing entirely.
                expect.strings("gated_refs").forEach { ref -> withClue(where) { boundRefs(lattice) shouldContain ref } }
            }
        }
    }
}

private fun assertGate(
    id: String,
    index: Int,
    expect: JsonObject,
    run: ConversationRun,
) {
    val where = "$id gate turn ${index + 1}"
    val result = run.gateResults[index]
    val accepted = result.outcomes.filter { it.accepted }

    expect["gated_refs"]?.let {
        withClue(where) { accepted.mapNotNull { o -> o.binding?.ref } shouldBe expect.strings("gated_refs") }
    }
    expect["evidence_classes"]?.let {
        withClue(where) {
            accepted.mapNotNull { o -> o.binding?.evidenceClass?.name } shouldBe
                expect.strings("evidence_classes")
        }
    }
    expect["proposing_rung"]?.let {
        // A hypothesis is not evidence — but a gated binding must carry WHO proposed it.
        accepted.forEach { withClue(where) { it.hypothesis.proposingRung shouldBe expect.strOr("proposing_rung", "") } }
    }
    expect["gap_kinds"]?.let {
        withClue(where) { result.updatedGaps.map { g -> g.kind.name } shouldBe expect.strings("gap_kinds") }
    }
}
