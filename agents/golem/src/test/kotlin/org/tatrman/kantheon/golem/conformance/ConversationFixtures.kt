package org.tatrman.kantheon.golem.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.golem.resolution.RecordedResolutionCore
import org.tatrman.kantheon.golem.resolution.ladder.GateCall
import org.tatrman.kantheon.golem.resolution.ladder.GateResult
import org.tatrman.kantheon.golem.resolution.ladder.HypothesisVerdict
import org.tatrman.kantheon.golem.resolution.ladder.foldGateResult
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.SourceTag
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P5.4 T2 — the SHARED conformance-conversation corpus, read.
 *
 * The files under `src/test/resources/conversations/` are byte-for-byte copies of
 * `tatrman-server:conformance/conversations/` (see their `PROVENANCE.md`), and their schema is
 * that repo's `conformance/conversations/SCHEMA.md`. RV-28 — *one corpus, one core, N shells* —
 * means the Python OS Golem and this one must pass **the same files unchanged**, so nothing in
 * this package may adapt a fixture: it either asserts what the file says or the fixture is
 * rejected by the guard in [ConformanceConversationsSpec].
 *
 * A turn names a LATTICE by golden id (`"lattice": "h2-cs"`) rather than inlining one, exactly
 * as the P2 gate fixtures do — the goldens are vendored separately under `lattice/`.
 */
object ConversationFixtures {
    /** The five heroes, pinned by name. A suite that silently loses a fixture reports green. */
    val IDS: List<String> =
        listOf("h1-answer", "h1prime-regate", "h2-ask-pin-resume", "h4-refusal", "h5-answer-with-gap")

    private val json = Json { ignoreUnknownKeys = true }

    fun bytes(id: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/conversations/$id.json")) {
            "missing vendored conversation fixture: conversations/$id.json"
        }.use { it.readBytes() }

    fun load(id: String): JsonObject = json.parseToJsonElement(bytes(id).toString(Charsets.UTF_8)).jsonObject
}

// ------------------------------------------------------------------ small JSON conveniences

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

internal fun JsonObject.strOr(
    key: String,
    fallback: String,
): String = str(key) ?: fallback

internal fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

internal fun JsonObject.arr(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

internal fun JsonObject.strings(key: String): List<String> = arr(key).map { it.jsonPrimitive.content }

internal fun JsonObject.intOr(
    key: String,
    fallback: Int,
): Int = this[key]?.jsonPrimitive?.int ?: fallback

internal fun JsonObject.boolOr(
    key: String,
    fallback: Boolean,
): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

// ------------------------------------------------------------------------- the recorded core

private fun span(raw: JsonObject?): Span =
    Span
        .newBuilder()
        .setStart(raw?.intOr("start", 0) ?: 0)
        .setEnd(raw?.intOr("end", 0) ?: 0)
        .setText(raw?.strOr("text", "") ?: "")
        .build()

/**
 * A fixture's recorded gate outcome → a real `Binding`.
 *
 * ⚑ Allowed **here and only here**, for the same reason golem-py allows it in its runner and
 * `SingleBinderFenceSpec` still passes: this IS a recorded `resolve.gate:v1` response standing
 * in for one the core produced. Production code never builds a binding (RV-7); the fence greps
 * `src/main`, and this file is test code.
 */
private fun binding(raw: JsonObject): Binding =
    Binding
        .newBuilder()
        .setRef(raw.strOr("ref", ""))
        .setTargetClass(TargetClass.valueOf(raw.strOr("target_class", "TARGET_CLASS_UNSPECIFIED")))
        .setEvidenceClass(EvidenceClass.valueOf(raw.strOr("evidence_class", "EVIDENCE_CLASS_UNSPECIFIED")))
        .setSource(SourceTag.valueOf(raw.strOr("source", "SOURCE_TAG_UNSPECIFIED")))
        .setInClassScore(raw["in_class_score"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
        .build()

private fun gap(raw: JsonObject): GapRecord =
    GapRecord
        .newBuilder()
        .setSpan(span(raw.obj("span")))
        .setKind(GapKind.valueOf(raw.strOr("kind", "GAP_KIND_UNSPECIFIED")))
        .setValueId(raw.strOr("value_id", ""))
        .setMentionId(raw.strOr("mention_id", ""))
        .setDisposition(Disposition.valueOf(raw.strOr("disposition", "DISPOSITION_UNRESOLVED")))
        .build()

internal fun hypothesis(raw: JsonObject): Hypothesis =
    Hypothesis
        .newBuilder()
        .setSpan(span(raw.obj("span")))
        .setRef(raw.strOr("ref", ""))
        .setCorrection(raw.strOr("correction", ""))
        .setProposingRung(raw.strOr("proposing_rung", ""))
        .build()

/**
 * The `ResolveResponse` a live door would return for this turn's `core:` block.
 *
 * Two details the schema makes load-bearing:
 *
 *  - **`drop_bindings`** is how H4 exists without a second golden: it IS H1's lattice minus the
 *    operator, because *"Proč"* is not one. Authoring a near-copy of `h1-cs` would have put two
 *    files out of step the first time either moved.
 *  - **`options` / `resume_token`** go onto `ResolveResponse.awaiting`, which is where the proto
 *    puts them — a member of the `oneof outcome`, not a field of the lattice. golem-py's own
 *    dataclass carries them on its lattice object; that is a shell-side representation choice,
 *    and mirroring it here would have hidden the very bug T2 found (see `CoreCallResult.awaiting`).
 */
internal fun recordedResponse(core: JsonObject): ResolveResponse {
    val id = requireNotNull(core.str("lattice")) { "a core block must name a lattice golden by id" }
    val dropped = core.strings("drop_bindings").toSet()

    var lattice: ResolutionState = RecordedResolutionCore.lattice(id)
    if (dropped.isNotEmpty()) {
        val b = lattice.toBuilder()
        // By index, not over `mentionsBuilderList`: `setMentions` structurally modifies the
        // list the builder view iterates, which is a ConcurrentModificationException.
        for (i in 0 until lattice.mentionsCount) {
            val mention = lattice.getMentions(i)
            val kept = mention.bindingsList.filterNot { it.ref in dropped }
            if (kept.size != mention.bindingsCount) {
                b.setMentions(i, Mention.newBuilder(mention).clearBindings().addAllBindings(kept))
            }
        }
        lattice = b.build()
    }

    val out =
        ResolveResponse
            .newBuilder()
            .setParse(RecordedResolutionCore.parse(id))
            .setResolutionState(lattice)
            .setTraceId("recorded-$id")

    val options = core.arr("options")
    val token = core.strOr("resume_token", "")
    if (options.isNotEmpty() || token.isNotEmpty()) {
        val awaiting = AwaitingClarification.newBuilder().setResumeToken(token)
        options.forEach { el ->
            val o = el.jsonObject
            awaiting.addOptions(
                Option
                    .newBuilder()
                    .setId(o.strOr("id", ""))
                    .setLabel(o.strOr("label", ""))
                    .setTargetRef(o.strOr("ref", "")),
            )
        }
        out.awaiting = awaiting.build()
    }
    return out.build()
}

/**
 * The `resolve.gate:v1` a fixture recorded, as a [GateCall].
 *
 * Counts its calls and keeps the hypotheses it was SENT — a fixture asserting `proposing_rung`
 * on a resume is making a claim about what the Golem proposed (RV-7), not about what the
 * recorded gate happened to echo back, and only the sent list can answer that.
 */
internal class RecordedGate(
    private val result: GateResult = GateResult(emptyList(), emptyList(), null, emptyList()),
) : GateCall {
    var calls: Int = 0
        private set
    val sent: MutableList<List<Hypothesis>> = mutableListOf()

    override suspend fun gate(
        lattice: ResolutionState,
        hypotheses: List<Hypothesis>,
    ): GateResult {
        calls++
        sent += hypotheses
        return result
    }
}

internal fun recordedGate(spec: JsonObject?): RecordedGate {
    if (spec == null) return RecordedGate()
    val outcomes =
        spec.arr("outcomes").map { el ->
            val o = el.jsonObject
            val accepted = o["accepted"]?.jsonPrimitive?.boolean ?: false
            HypothesisVerdict(
                hypothesis = hypothesis(o.obj("hypothesis") ?: JsonObject(emptyMap())),
                accepted = accepted,
                reason = o.strOr("reason", ""),
                // Paired with its hypothesis, which is the only route back to a mention.
                binding = if (accepted) o.obj("binding")?.let(::binding) else null,
            )
        }
    val bindings = outcomes.mapNotNull { it.binding }
    val entry =
        spec.obj("rung_log_entry")?.let { e ->
            RungLogEntry
                .newBuilder()
                .setRound(e.intOr("round", 0))
                .setRung(e.strOr("rung", ""))
                .setAction(e.strOr("action", ""))
                .setBindingsAdded(e.intOr("bindings_added", 0))
                .build()
        }
    return RecordedGate(GateResult(bindings, spec.arr("updated_gaps").map { gap(it.jsonObject) }, entry, outcomes))
}

/**
 * Merge a gate result into a lattice — the bare `resolve.gate:v1` turn's whole job.
 *
 * Delegates to the **production** [foldGateResult] rather than re-implementing the attach: a
 * test-only fold would have quietly kept passing while the real one was missing, which is the
 * exact bug this tier exists to catch.
 */
internal fun applyGateResult(
    lattice: ResolutionState,
    result: GateResult,
): ResolutionState = foldGateResult(lattice, result.outcomes, result.updatedGaps, result.rungLogEntry)
