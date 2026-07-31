package org.tatrman.kantheon.iris.protocol.record

import io.opentelemetry.api.trace.Span
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sources the [org.tatrman.kantheon.protocol.v1.RecordPointers] fields that come
 * from ambient context rather than from the turn's payload — the trace id and
 * the log window (contracts §1, architecture §1).
 *
 * Everything here **degrades to a stable empty value instead of throwing**. A
 * missing trace id costs the protocol its Loki/Tempo sections for that turn; a
 * thrown exception would cost the record entirely, and the record is the one
 * artefact that outlives every source it points at (PT-4). Absence is a fact the
 * assembler can report in the receipts; a missing row is not.
 */
object PointerSourcing {
    /** Slack applied to both ends of the log window. */
    private const val WINDOW_SLACK_SECONDS = 2L

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * The current span's trace id, or `""` when there is no valid span.
     *
     * Invalid is the honest default in three real situations: telemetry disabled
     * by config, a code path outside any server span, and — the one that bit P0 —
     * an SDK whose propagators never got wired, where a span exists locally but
     * its context did not travel. `""` means "do not query Tempo for this turn".
     */
    fun traceIdOrEmpty(): String = Span.current().spanContext.let { if (it.isValid) it.traceId else "" }

    /**
     * The window a log query should cover for this turn, ISO-8601 with offset.
     *
     * Widened by [WINDOW_SLACK_SECONDS] at both ends because the turn's own
     * timestamps are taken in the BFF, while the logs being searched are written
     * by other services on other clocks: the first line of a turn is frequently
     * stamped a few hundred milliseconds *before* the BFF starts counting, and
     * the last flush lands after it stops. A window that exactly matched the
     * BFF's view would silently clip both ends of every turn.
     */
    fun logWindow(
        startedAt: Instant,
        completedAt: Instant,
    ): Pair<String, String> =
        startedAt.minusSeconds(WINDOW_SLACK_SECONDS).iso() to
            completedAt.plusSeconds(WINDOW_SLACK_SECONDS).iso()

    private fun Instant.iso(): String = atOffset(ZoneOffset.UTC).format(ISO)
}
