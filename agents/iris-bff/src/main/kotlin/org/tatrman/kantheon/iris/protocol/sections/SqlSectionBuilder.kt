package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.SqlSection

/**
 * `protocol.section.sql` — the statement actually submitted to an engine.
 *
 * Sourced from the record's `sql_inline` pointer, which golem set from the SQL it
 * really executed. `sql_ref` is accepted but not dereferenced here: fetching it
 * is I/O and builders are pure (architecture §3.1), so a ref-only turn degrades
 * with the ref visible rather than silently showing nothing — which needs the
 * dedicated `SqlSection.sql_ref` field. It used to ride in `engine_label`, and the
 * renderer prints neither `engine_label` nor `dialect`, so "the ref visible" was a
 * claim about a document that showed an empty code fence (review-080 R6).
 *
 * `literals_masked` is left FALSE here on purpose — masking is the redactor's
 * job, and a builder that pre-masked would let the profile's decision bypass the
 * redaction chain where it can be audited.
 */
object SqlSectionBuilder {
    const val KEY: String = "protocol.section.sql"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val p = input.record.pointers
            if (p.sqlInline.isBlank()) {
                val degraded = SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED)
                if (p.sqlRef.isNotBlank()) degraded.setSql(SqlSection.newBuilder().setSqlRef(p.sqlRef))
                return@guarded degraded.build()
            }
            val (sql, truncated) = SectionShape.cap(p.sqlInline, input.caps.sqlChars)
            SectionShape
                .start(KEY, verbosity)
                .setTruncated(truncated)
                .setSql(SqlSection.newBuilder().setSql(sql).setLiteralsMasked(false))
                .build()
        }
}
