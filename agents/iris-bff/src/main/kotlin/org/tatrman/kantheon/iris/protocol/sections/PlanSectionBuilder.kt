package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.PlanSection
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus

/**
 * `protocol.section.plan` — the translator's RelPlan.
 *
 * S-1's `reconstructed` flag is passed through verbatim and never inferred here.
 * `true` means the turn carried no plan and the translator was asked to explain
 * the SQL after the fact, so the reader is looking at *a* plan for that SQL, not
 * provably the one that ran. Presenting a reconstruction as the original is the
 * one thing this section must never do.
 *
 * **Known gap.** The explain fallback is currently the section's ONLY source. A turn
 * that carried its own `plan_ids` gets them recorded in `RecordPointers`, the
 * assembler correctly skips reconstruction for it (S-1: never reconstruct what the
 * turn already had) — and then nothing resolves those ids into plan text, so the
 * section degrades on exactly the turns that had the best plan to show. Closing it
 * needs a plan-by-id read surface on ttr-translate; until then the ids are captured
 * and unused, which is at least recoverable after the fact.
 */
object PlanSectionBuilder {
    const val KEY: String = "protocol.section.plan"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            SectionShape.notReached(KEY, input, verbosity)?.let { return@guarded it }
            SectionShape.notConsulted(KEY, input, verbosity)?.let { return@guarded it }
            val e = input.sources.explain
            when {
                e.status == SourceStatus.OK && e.relPlanText.isNotBlank() ->
                    SectionShape
                        .start(KEY, verbosity)
                        .setPlan(
                            PlanSection
                                .newBuilder()
                                .setRelPlanText(e.relPlanText)
                                .setReconstructed(e.reconstructed),
                        ).build()

                // NOT `off`. `SECTION_OFF` means *the operator suppressed this section* —
                // the renderer drops it silently, and rightly so. A source that is merely
                // unwired is a different fact: the profile asked for `plan = full` and the
                // reader got no Plan heading at all, with the only trace of it a line in
                // the receipts. Degradation belongs inside the document (P-4), so the
                // heading stays and says it is unavailable.
                //
                // The profile's own OFF is still honoured — `SectionShape.guarded` returns
                // `off(key)` before this block ever runs.
                else ->
                    SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
        }
}
