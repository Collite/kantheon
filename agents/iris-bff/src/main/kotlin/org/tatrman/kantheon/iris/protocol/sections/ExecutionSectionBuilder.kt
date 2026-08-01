package org.tatrman.kantheon.iris.protocol.sections

import org.tatrman.kantheon.iris.protocol.sources.SourceStatus
import org.tatrman.kantheon.protocol.v1.ExecutionSection
import org.tatrman.kantheon.protocol.v1.Section
import org.tatrman.kantheon.protocol.v1.SectionStatus

/**
 * `protocol.section.execution` — where the query ran and what came back.
 *
 * Read from Tempo spans rather than from the agent's hints: the dispatch target
 * and worker are facts about the estate, and the trace is the only source that
 * observed them first-hand. Row count prefers the span attribute for the same
 * reason.
 */
object ExecutionSectionBuilder {
    const val KEY: String = "protocol.section.execution"

    private const val ATTR_TARGET = "dispatch.target"
    private const val ATTR_WORKER = "dispatch.worker"
    private const val ATTR_ROWS = "result.row_count"

    fun build(input: SectionInput): Section =
        SectionShape.guarded(KEY, input) { verbosity ->
            SectionShape.notConsulted(KEY, input, verbosity)?.let { return@guarded it }
            val tempo = input.sources.tempo
            if (tempo.status != SourceStatus.OK) {
                return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()
            }
            // ONLY a span that names a dispatch target counts. The previous fallback —
            // "otherwise take the longest span" — produced a section that was confidently
            // wrong on a live cluster: with the tatrman-server hops untraced, the longest
            // span is the BFF's own request, so the document reported
            // `Worker: iris-bff, Duration: 17403 ms` — the whole TURN's duration presented
            // as the query's, and the BFF named as the thing that executed it.
            //
            // A section with nothing to say must degrade and let the receipts explain
            // (P-4). Guessing is the one failure mode this document cannot afford: a
            // reader can work with "unavailable", but not with a plausible falsehood.
            val span =
                tempo.spans.firstOrNull { it.attributes.containsKey(ATTR_TARGET) }
                    ?: return@guarded SectionShape.start(KEY, verbosity, SectionStatus.SECTION_DEGRADED).build()

            SectionShape
                .start(KEY, verbosity)
                .setExecution(
                    ExecutionSection
                        .newBuilder()
                        .setDispatchTarget(span.attributes[ATTR_TARGET].orEmpty())
                        .setWorker(span.attributes[ATTR_WORKER] ?: span.serviceName)
                        .setRowCount(span.attributes[ATTR_ROWS]?.toLongOrNull() ?: -1)
                        .setDurationMs(span.durationMs),
                ).build()
        }
}
