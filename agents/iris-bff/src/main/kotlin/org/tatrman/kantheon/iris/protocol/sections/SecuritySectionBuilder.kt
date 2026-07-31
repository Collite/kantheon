package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.SecurityRuleView
import org.tatrman.kantheon.protocol.v1.SecuritySection
import org.tatrman.validate.v1.SecurityRuleApplied

/**
 * `protocol.section.security` — the row-level rules the validator injected.
 *
 * **Today this section is always degraded, by Amendment A-1**: `security_applied`
 * is consumed inside ttr-query and carried on none of its response types, so the
 * F7 capture is structurally unreachable and the recorder writes an explicit
 * `capture_gaps` marker instead. That marker is why this builder can distinguish
 * "no rules applied" — a real, reportable answer — from "we could not look",
 * which it must never present as the former.
 *
 * The parse path below is live code, not speculation: the day ttr-query carries
 * the set, the recorder fills `captures.security_applied` and this section starts
 * answering without another change here.
 */
object SecuritySectionBuilder {
    const val KEY: String = "protocol.section.security"
    const val CAPTURE: String = "security_applied"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val gap =
                input.record.pointers.captureGapsList
                    .firstOrNull { it.capture == CAPTURE }
            val bytes = input.record.captures.securityApplied

            if (bytes.isEmpty) {
                // A gap marker means "unavailable"; its absence with empty bytes means
                // the turn genuinely applied no rules. Same empty value, opposite facts.
                val status = if (gap != null) SectionStatus.SECTION_DEGRADED else SectionStatus.SECTION_OK
                return@guarded SectionShape
                    .start(KEY, verbosity, status)
                    .setSecurity(SecuritySection.newBuilder().setPolicySource(gap?.reason.orEmpty()))
                    .build()
            }

            val applied = SecurityRuleApplied.parseFrom(bytes)
            SectionShape
                .start(KEY, verbosity)
                .setSecurity(
                    SecuritySection
                        .newBuilder()
                        .setPolicySource("ttr-validate")
                        .addRules(
                            SecurityRuleView
                                .newBuilder()
                                .setRuleId(applied.ruleId)
                                .setDescription(applied.predicateSummary)
                                // A-1: the upstream deliberately omits the predicate body
                                // ("leak-safe"), so it is masked at the SOURCE — not by our
                                // redactor. Reporting it as masked is the honest rendering.
                                .setPredicate("")
                                .setPredicateMasked(true),
                        ),
                ).build()
        }
}
