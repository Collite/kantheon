package org.tatrman.kantheon.sysifos.bff.stream

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tatrman.kantheon.sysifos.bff.write.DraftEventSink
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session in-memory event bus backing the SSE `/stream` (contracts §3.6).
 * The draft state machine publishes `DraftAck`/`DraftCommitted`/`DraftRejected`/
 * `BatchRowResult`; the stream route subscribes per connected session and writes
 * each event as an SSE frame. A small replay buffer smooths the publish-before-
 * subscribe race for events that fire right after a draft POST.
 */
class SessionStreamBus {
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<SysifosStreamEvent>>()

    private fun flowFor(sessionId: String): MutableSharedFlow<SysifosStreamEvent> =
        flows.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 16, extraBufferCapacity = 64)
        }

    /** Events for a session (hot; never completes). */
    fun events(sessionId: String): SharedFlow<SysifosStreamEvent> = flowFor(sessionId).asSharedFlow()

    suspend fun publish(
        sessionId: String,
        event: SysifosStreamEvent,
    ) = flowFor(sessionId).emit(event)

    /** A [DraftEventSink] scoped to one session — handed to the draft state machine. */
    fun sinkFor(sessionId: String): DraftEventSink = DraftEventSink { event -> publish(sessionId, event) }
}
