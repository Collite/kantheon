package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.LogLine
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus
import org.tatrman.kantheon.protocol.v1.ServiceLogGroup
import org.tatrman.kantheon.protocol.v1.ServiceLogsSection
import org.tatrman.kantheon.protocol.v1.Verbosity

/**
 * `protocol.section.service-logs` — what each service said during the turn.
 *
 * The cap (`caps.service-logs-lines`, PT-10) is applied per group and the overflow
 * is COUNTED, not dropped silently: `dropped_by_cap` plus `Section.truncated`
 * lets the document say "there were 340 more lines" instead of implying the
 * service went quiet. A log section that hides its own truncation is worse than
 * no log section.
 */
object ServiceLogsSectionBuilder {
    const val KEY: String = "protocol.section.service-logs"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            val loki = input.sources.loki
            if (loki.status != SourceStatus.OK) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }

            // At summary, only lines that carry a signal — a reader skimming wants the
            // warnings and errors, not every debug line the turn produced.
            val keepLine: (String) -> Boolean =
                if (verbosity == Verbosity.VERBOSITY_SUMMARY) {
                    { level -> level.uppercase() in setOf("WARN", "WARNING", "ERROR", "FATAL") }
                } else {
                    { true }
                }

            var truncated = false
            val b = ServiceLogsSection.newBuilder()
            loki.groups.forEach { group ->
                val eligible = group.lines.filter { keepLine(it.level) }
                val kept = eligible.take(input.caps.serviceLogsLines)
                val dropped = group.droppedByCap + (eligible.size - kept.size)
                if (dropped > 0) truncated = true
                b.addGroups(
                    ServiceLogGroup
                        .newBuilder()
                        .setServiceName(group.serviceName)
                        .setDroppedByCap(dropped)
                        .addAllLines(
                            kept.map {
                                LogLine
                                    .newBuilder()
                                    .setTs(it.ts)
                                    .setLevel(it.level)
                                    .setBody(it.body)
                                    .setTraceId(it.traceId)
                                    .build()
                            },
                        ),
                )
            }
            SectionShape
                .start(KEY, verbosity)
                .setTruncated(truncated)
                .setServiceLogs(b)
                .build()
        }
}
