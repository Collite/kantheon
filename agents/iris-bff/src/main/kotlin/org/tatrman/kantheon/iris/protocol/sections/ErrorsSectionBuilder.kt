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

            if (input.sources.loki.status == SourceStatus.OK) {
                input.sources.loki.groups.forEach { group ->
                    group.lines
                        .filter { it.level.uppercase() in ERROR_LEVELS }
                        .forEach { line ->
                            b.addItems(
                                ErrorItem
                                    .newBuilder()
                                    .setSource(group.serviceName)
                                    .setCode(line.level.uppercase())
                                    .setMessage(line.body),
                            )
                        }
                }
            }

            SectionShape.start(KEY, verbosity).setErrors(b).build()
        }
}
