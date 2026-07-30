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
            val gw = input.sources.gateway
            if (gw.status != SourceStatus.OK) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }

            val traceId = input.record.pointers.traceId
            val turnRef = input.record.pointers.gatewayTurnRef
            val declaredRefs =
                input.record.pointers.llmCallRefsList
                    .toSet()

            val (mine, theirs) =
                gw.items.partition { call ->
                    (turnRef.isNotBlank() && call.turnRef == turnRef) ||
                        (traceId.isNotBlank() && call.traceId == traceId) ||
                        call.id in declaredRefs
                }

            val b = LlmCallsSection.newBuilder().setUnattributableCount(theirs.size)
            mine.forEach { call -> b.addCalls(call.toProto(verbosity)) }

            SectionShape.start(KEY, verbosity).setLlmCalls(b).build()
        }

    private fun GatewayCall.toProto(verbosity: Verbosity): LlmCall.Builder {
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
        if (verbosity == Verbosity.VERBOSITY_FULL) {
            if (promptText.isNotEmpty()) {
                b.addMessages(LlmMessage.newBuilder().setRole("user").setContent(promptText))
            }
            if (responseText.isNotEmpty()) {
                b.addMessages(LlmMessage.newBuilder().setRole("assistant").setContent(responseText))
            }
        }
        return b
    }
}
