package org.tatrman.kantheon.iris.stream

/**
 * Transport-level SSE frame accumulator: feed it raw lines (CRLF already stripped)
 * and it hands `(event, data)` to [onFrame] on each blank-line frame terminator.
 * Comment lines (`: ready`, `: ping`) are keepalives, not frames, and are dropped.
 *
 * Deliberately untyped — it knows nothing about which event family it is carrying,
 * so both the native-Golem client and any future agent client can share one wire
 * reader. (The transitional `golemv2` package keeps its own typed accumulator; it
 * is deleted at the Golem cutover and is not worth re-plumbing on the way out.)
 */
class SseFrameAccumulator(
    private val onFrame: (event: String, data: String) -> Unit,
) {
    private var event: String? = null
    private val data = StringBuilder()

    fun onLine(line: String) {
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit // comment frame — keepalive, not an event
            // SSE strips exactly one optional leading space after the colon — not a full
            // trim, which would corrupt whitespace-significant data.
            line.startsWith("event:") -> event = line.removePrefix("event:").removePrefix(" ")
            line.startsWith("data:") -> {
                // Multi-`data:` frames join with \n per the SSE spec.
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }

    /** Emit the frame in progress, if any, and reset. Call once at stream close. */
    fun flush() {
        val e = event
        if (e != null) onFrame(e, data.toString())
        event = null
        data.clear()
    }

    companion object {
        /** Feed a whole response body through an accumulator (tolerates `\r\n`). */
        fun consume(
            body: String,
            onFrame: (event: String, data: String) -> Unit,
        ) {
            val acc = SseFrameAccumulator(onFrame)
            body.split("\n").forEach { acc.onLine(it.removeSuffix("\r")) }
            acc.flush()
        }
    }
}
