package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.ErrorItem
import org.tatrman.kantheon.protocol.v1.ErrorsSection
import org.tatrman.kantheon.protocol.v1.Section

/**
 * `protocol.section.errors` — everything that went wrong, gathered from wherever
 * it surfaced: the turn's own terminal status, and ERROR-level log lines from any
 * service that spoke during the window.
 *
 * Unlike every other section this one does NOT degrade when a source is missing.
 * An errors section that said "degraded" because Loki was down would be read as
 * "there may have been errors"; what it can always state truthfully is what the
 * turn itself reported. Absent log input simply contributes nothing.
 */
object ErrorsSectionBuilder {
    const val KEY: String = "protocol.section.errors"

    private val ERROR_LEVELS = setOf("ERROR", "FATAL", "SEVERE")

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val b = ErrorsSection.newBuilder()

            if (input.turn.status.equals("failed", ignoreCase = true)) {
                b.addItems(
                    ErrorItem
                        .newBuilder()
                        .setSource("iris-bff")
                        .setCode("TURN_FAILED")
                        .setMessage("the turn terminated with status 'failed'"),
                )
            }

            // Log-derived errors are read from the RAW source, not from the service-logs
            // section's profile-filtered view: `service-logs = summary` must never hide an
            // error, only shorten the log listing.
            //
            // Capped for the same reason every other section is (PT-10, review-080 R12):
            // a turn that failed 900 times would otherwise put 900 items in a document
            // that already renders those same lines under service-logs. The cap is
            // `errors-items`, deliberately NOT the log cap — borrowing that one would
            // let `service-logs = summary` bound the errors it must never hide.
            var truncated = false
            if (input.sources.loki.status == SourceStatus.OK && input.sources.describes(input.turn.turnId)) {
                val lines =
                    input.sources.loki.groups.flatMap { group ->
                        group.lines.filter { it.level.uppercase() in ERROR_LEVELS }.map { group.serviceName to it }
                    }
                val kept = lines.take(input.caps.errorItems)
                truncated = kept.size < lines.size
                kept.forEach { (service, line) ->
                    b.addItems(
                        ErrorItem
                            .newBuilder()
                            .setSource(service)
                            .setCode(line.level.uppercase())
                            .setMessage(line.body),
                    )
                }
            }

            SectionShape
                .start(KEY, verbosity)
                .setTruncated(truncated)
                .setErrors(b)
                .build()
        }
}
