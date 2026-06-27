package org.tatrman.kantheon.iris.stream

import com.google.protobuf.util.JsonFormat
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * Serialise an `IrisStreamEvent` to an SSE frame (contracts §2.3): the event
 * name is the set oneof case, the data line is the whole event as proto-JSON.
 */
object IrisSse {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    fun eventName(ev: IrisStreamEvent): String =
        when (ev.eventCase) {
            IrisStreamEvent.EventCase.ENVELOPE -> "envelope"
            IrisStreamEvent.EventCase.STEP -> "step"
            IrisStreamEvent.EventCase.TOOL_CALL -> "tool_call"
            IrisStreamEvent.EventCase.THINKING -> "thinking"
            IrisStreamEvent.EventCase.ERROR -> "error"
            IrisStreamEvent.EventCase.DONE -> "done"
            IrisStreamEvent.EventCase.EVENT_NOT_SET -> "message"
        }

    /** A complete SSE frame: `event: <case>\ndata: <json>\n\n`. */
    fun frame(ev: IrisStreamEvent): String = "event: ${eventName(ev)}\ndata: ${printer.print(ev)}\n\n"
}
