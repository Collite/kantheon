package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.protocol.v1.QuerySection
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse

/**
 * `protocol.section.query` — the entity-level query the turn resolved to, before
 * it became a plan or SQL. Read off the F2 capture (`function_id` + args), which
 * is the only place the *intent* survives: by the time SQL exists the question
 * has already been compiled away.
 */
object QuerySectionBuilder {
    const val KEY: String = "protocol.section.query"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val bytes = input.record.captures.resolveResponse
            if (bytes.isEmpty) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
            val resolved = ResolveResponse.parseFrom(bytes)
            if (resolved.outcomeCase != ResolveResponse.OutcomeCase.RESOLUTION) {
                // No resolution means no query was ever formed — an honest empty,
                // not a degradation: nothing failed, there is simply nothing to show.
                return@guarded SectionShape.start(KEY, verbosity).setQuery(QuerySection.getDefaultInstance()).build()
            }
            val r = resolved.resolution
            SectionShape
                .start(KEY, verbosity)
                .setQuery(
                    QuerySection
                        .newBuilder()
                        .setEntityQuery(listOf(r.functionId, r.argsJson).filter { it.isNotBlank() }.joinToString(" "))
                        .setQueryKind(r.intentKind.name),
                ).build()
        }
}
