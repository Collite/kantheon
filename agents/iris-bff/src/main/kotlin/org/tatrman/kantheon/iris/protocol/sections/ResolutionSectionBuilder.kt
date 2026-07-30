package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.EntityBindingView
import org.tatrman.kantheon.protocol.v1.ResolutionSection
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.Verbosity
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse

/**
 * `protocol.section.resolution` — renders the F2 capture: what Themis understood
 * and why it routed where it did.
 *
 * This is the section the whole record-backbone decision (PT-4/PT-5) was made
 * for. The `ResolveResponse` is captured in-band at turn time precisely because
 * it cannot be reconstructed from logs afterwards — so if the capture is absent,
 * the section degrades rather than guessing from the routing outcome.
 */
object ResolutionSectionBuilder {
    const val KEY: String = "protocol.section.resolution"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val bytes = input.record.captures.resolveResponse
            if (bytes.isEmpty) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
            val resolved = ResolveResponse.parseFrom(bytes)
            val b = ResolutionSection.newBuilder()

            when (resolved.outcomeCase) {
                ResolveResponse.OutcomeCase.RESOLUTION -> {
                    val r = resolved.resolution
                    b.functionId = r.functionId
                    b.argsJson = r.argsJson
                    b.rationale = r.rationale
                    // Resolution.confidence is the UNDERSTANDING confidence; the routing
                    // decision carries its own. This section reports the former — "how
                    // sure was it what you asked", not "how sure was it who should answer".
                    b.confidence = r.confidence
                    if (r.hasRouting()) {
                        b.layerHit = r.routing.layerHit
                        b.needsUserPickShown = r.routing.needsUserPick
                        r.routing.alternatesList.forEach { b.addAlternatesOffered(it.agentId.value) }
                    }
                    // Bindings are the reader's answer to "what did it think I meant?" —
                    // the single most useful line when a turn returned the wrong rows.
                    // EntityBinding is a oneof: universal (dates, amounts — normalised by
                    // an engine) or domain (fuzzy-matched to a catalog id).
                    r.bindingsList.forEach { binding -> b.addBindings(binding.toView()) }
                }

                ResolveResponse.OutcomeCase.AWAITING ->
                    b.rationale = resolved.awaiting.question

                ResolveResponse.OutcomeCase.REFUSAL -> {
                    b.rationale = resolved.refusal.rationale
                    resolved.refusal.gapsList.forEach { b.addAlternatesOffered(it.kind.name) }
                }

                else -> Unit
            }

            // At summary the reasoning prose goes; the decision itself stays. A
            // summary must still answer "what did it do", only not "why at length".
            if (verbosity == Verbosity.VERBOSITY_SUMMARY) {
                b.clearRationale()
                b.clearArgsJson()
            }

            SectionShape.start(KEY, verbosity).setResolution(b).build()
        }

    /**
     * Flatten a themis `EntityBinding` oneof into the document's own view type.
     * A domain binding's `resolved_id` is the real answer to "what did it bind
     * to"; a universal one has no catalog id, so its normalised value stands in.
     * Confidence exists only on domain bindings' fuzzy candidates — a universal
     * binding is a parse, not a guess, so it reports 1.0.
     */
    private fun org.tatrman.kantheon.themis.v1.Themis.EntityBinding.toView(): EntityBindingView.Builder =
        EntityBindingView.newBuilder().also { v ->
            when {
                hasDomain() -> {
                    v.mention = domain.rawText
                    v.boundRef = domain.resolvedId.ifBlank { domain.resolvedLabel }
                    v.confidence = domain.alternativesList.firstOrNull()?.score ?: 1.0
                }

                hasUniversal() -> {
                    v.mention = universal.rawText
                    v.boundRef = universal.normalizedValue
                    v.confidence = 1.0
                }
            }
        }
}
