package org.tatrman.kantheon.iris.api

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.iris.inbox.InboxItem
import org.tatrman.kantheon.iris.inbox.InboxService
import org.tatrman.kantheon.iris.inbox.InboxView
import org.tatrman.kantheon.iris.inbox.LifecycleEvent
import org.tatrman.kantheon.iris.inbox.LifecycleHub
import org.tatrman.kantheon.iris.stream.respondSse

@Serializable
data class InboxItemDto(
    val investigationId: String,
    val question: String,
    val status: String,
    val rawStatus: String,
    val origin: String,
    val costSoFar: Double,
    val updatedAt: String,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val turnId: String? = null,
    val partial: Boolean = false,
)

@Serializable
data class InboxCountsDto(
    val running: Int,
    val needsInput: Int,
)

@Serializable
data class InboxViewDto(
    val items: List<InboxItemDto>,
    val counts: InboxCountsDto,
)

private fun InboxItem.toDto() =
    InboxItemDto(
        investigationId,
        question,
        status.name,
        rawStatus,
        origin,
        costSoFar,
        updatedAt,
        sessionId,
        sessionTitle,
        turnId,
        partial,
    )

private fun InboxView.toDto() =
    InboxViewDto(items.map { it.toDto() }, InboxCountsDto(counts.running, counts.needsInput))

/**
 * Investigation inbox surface (PD-2, contracts §2.7). `GET /v1/inbox` returns the
 * aggregated view; `GET /v1/inbox/stream` is a user-scoped SSE that seeds with the
 * current view then re-emits an `inbox_event` on every [LifecycleHub] event (fed
 * by NATS in the Pythia arc, or the polling fallback now). Live-NATS reattach →
 * integration suite.
 */
fun Route.inboxRoutes(
    service: InboxService,
    hub: LifecycleHub,
    auth: BearerAuthenticator,
    heartbeatMs: Long = 15_000,
) {
    get("/v1/inbox") {
        val caller = call.requireCaller(auth) ?: return@get
        call.respond(service.build(caller).toDto())
    }

    get("/v1/inbox/stream") {
        val caller = call.requireCaller(auth) ?: return@get
        call.respondSse(heartbeatMs) { emit ->
            emit(frame(service.build(caller)))
            // CONFLATED: each event only signals "something changed → re-aggregate", so a
            // slow/stalled SSE consumer collapses pending events to the latest instead of
            // buffering unboundedly. `trySend` never suspends or throws on a closed channel.
            val channel = Channel<LifecycleEvent>(Channel.CONFLATED)
            val sub = hub.subscribe(caller.userId, caller.bearer) { channel.trySend(it) }
            try {
                for (event in channel) {
                    // Coarse event → re-aggregate the whole view (cheap; the list is small).
                    emit(frame(service.build(caller)))
                }
            } finally {
                sub.close()
                channel.close()
            }
        }
    }
}

private val inboxJson = Json { encodeDefaults = true }

private fun frame(view: InboxView): String = "event: inbox_event\ndata: ${inboxJson.encodeToString(view.toDto())}\n\n"
