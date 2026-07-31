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
 */
object PlanSectionBuilder {
    const val KEY: String = "protocol.section.plan"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
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

                e.status == SourceStatus.SKIPPED_BY_CONFIG ->
                    SectionShape.off(KEY)

                else ->
                    SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
        }
}
