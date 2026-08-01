package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.sources.GatewayCall
import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.LlmCall
import org.tatrman.kantheon.protocol.v1.LlmCallsSection
import org.tatrman.kantheon.protocol.v1.LlmMessage
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * `protocol.section.llm-calls` — the model calls behind the turn (PT-20/21).
 *
 * **Attribution is by turn ref or trace id, never by time window.** Two users'
 * turns overlap constantly, so "gateway rows from this minute" would mix them;
 * the whole point of carrying `turn_ref` is that attribution is exact. A row that
 * matches neither key is *not* silently included — it raises
 * `unattributable_count`, which the reader can weigh.
 *
 * Bodies are attached here and redacted downstream ([org.tatrman.kantheon.iris.protocol.redact]),
 * never trimmed here: a builder that pre-digested prompts would make the
 * redactor's floor unauditable.
 */
object LlmCallsSectionBuilder {
    const val KEY: String = "protocol.section.llm-calls"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            // Without this, turns off the anchor rendered `_no attributable calls_` with
            // SECTION_OK — a claim that no model calls happened, when none were looked
            // for. The filter below is exact, so the emptiness looked like an answer
            // (review-080 R1; the same defect kantheon#40 describes for log lines).
            SectionShape.notConsulted(KEY, input, verbosity)?.let { return@guarded it }

            val gw = input.sources.gateway
            if (gw.status != SourceStatus.OK) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }

            val traceId = input.record.pointers.traceId
            val turnRef = input.record.pointers.gatewayTurnRef
            val declaredRefs =
                input.record.pointers.llmCallRefsList
                    .toSet()

            // **turn_ref beats trace_id** (A-7), on this side of the wire too. The
            // gateway repo implements the precedence; this partition used to OR the
            // keys, so the rule held in one repo and its inverse in the other. A trace
            // covering more than one turn is exactly when that matters.
            val (mine, theirs) =
                gw.items.partition { call ->
                    when {
                        call.id in declaredRefs -> true
                        turnRef.isNotBlank() -> call.turnRef == turnRef
                        traceId.isNotBlank() -> call.traceId == traceId
                        else -> false
                    }
                }

            val b = LlmCallsSection.newBuilder().setUnattributableCount(theirs.size)
            var truncated = false
            mine.forEach { call ->
                val (proto, bit) = call.toProto(verbosity, input.caps.llmMessageChars)
                if (bit) truncated = true
                b.addCalls(proto)
            }

            SectionShape
                .start(KEY, verbosity)
                .setTruncated(truncated)
                .setLlmCalls(b)
                .build()
        }

    /**
     * @return the call, and whether the `llm-message-chars` cap bit on any body.
     *
     * The cap is a *size* limit and is distinct from the redactor's digesting,
     * which is a *policy* limit: one stops a single 400 kB prompt from swallowing
     * the document, the other decides how much of it this reader may see. Both
     * can apply, and a body that hits the cap is flagged `content_redacted` so
     * the reader is never shown a truncated prompt as if it were whole.
     */
    private fun GatewayCall.toProto(
        verbosity: Verbosity,
        maxChars: Int,
    ): Pair<LlmCall.Builder, Boolean> {
        val b =
            LlmCall
                .newBuilder()
                .setCallRef(id)
                // A-2: carried so FloorRedactor can verify attribution independently.
                .setTurnRef(turnRef)
                .setPurpose(purpose)
                .setRequestedModel(requestedModel)
                .setServedModel(servedModel)
                .setServedProvider(servedProvider)
                .setFallbackFrom(fallbackFrom.orEmpty())
                .setCached(cached)
                .setTokensPrompt(tokensPrompt)
                .setTokensCompletion(tokensCompletion)
                .setDurationMs(durationMs)
                .setTtfbMs(ttfbMs)
                .setCostUsd(costUsd)
                .setStatus(status)

        // At summary the metrics stay and the bodies go — cost, latency and model
        // are the operator's view of a turn; the prose is the debugger's.
        var truncated = false
        if (verbosity == Verbosity.VERBOSITY_FULL) {
            fun message(
                role: String,
                text: String,
            ) {
                if (text.isEmpty()) return
                val (body, bit) = SectionShape.cap(text, maxChars)
                if (bit) truncated = true
                b.addMessages(
                    LlmMessage
                        .newBuilder()
                        .setRole(role)
                        .setContent(body)
                        .setContentRedacted(bit),
                )
            }
            message("user", promptText)
            message("assistant", responseText)
        }
        return b to truncated
    }
}
