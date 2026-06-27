package org.tatrman.kantheon.sysifos.bff.stream

import com.google.protobuf.util.JsonFormat
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent

/**
 * Serialise a `SysifosStreamEvent` to an SSE frame (contracts §3.6): the event
 * name is the set oneof case, the data line is the whole event as proto-JSON.
 */
object SysifosSse {
    // alwaysPrintFieldsWithNoPresence: proto3 scalars/enums equal to their default
    // (e.g. BatchRowResult.row_index = 0, outcome = BR_COMMITTED, LoaderProgress.phase
    // = LP_PARSING) must still appear on the wire — the FE keys off their presence, so
    // omitting them silently drops the first grid row's result and the parsing phase.
    private val printer = JsonFormat.printer().alwaysPrintFieldsWithNoPresence().omittingInsignificantWhitespace()

    fun eventName(ev: SysifosStreamEvent): String =
        when (ev.eventCase) {
            SysifosStreamEvent.EventCase.DRAFT_ACK -> "draft_ack"
            SysifosStreamEvent.EventCase.DRAFT_COMMITTED -> "draft_committed"
            SysifosStreamEvent.EventCase.DRAFT_REJECTED -> "draft_rejected"
            SysifosStreamEvent.EventCase.LOADER_PROGRESS -> "loader_progress"
            SysifosStreamEvent.EventCase.LOADER_PREVIEW_READY -> "loader_preview_ready"
            SysifosStreamEvent.EventCase.ENVELOPE_BLOCK -> "envelope_block"
            SysifosStreamEvent.EventCase.ERROR -> "error"
            SysifosStreamEvent.EventCase.BATCH_ROW_RESULT -> "batch_row_result"
            SysifosStreamEvent.EventCase.HEARTBEAT -> "heartbeat"
            SysifosStreamEvent.EventCase.EVENT_NOT_SET -> "message"
        }

    /** A complete SSE frame: `event: <case>\ndata: <json>\n\n`. */
    fun frame(ev: SysifosStreamEvent): String = "event: ${eventName(ev)}\ndata: ${printer.print(ev)}\n\n"
}
