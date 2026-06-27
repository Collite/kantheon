package org.tatrman.kantheon.hebe.channels.web

import org.tatrman.kantheon.hebe.api.IncomingMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

object Routes {
    private val logger = LoggerFactory.getLogger(Routes::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("UNUSED_PARAMETER", "LongMethod")
    fun register(
        routing: Route,
        channel: WebChannel,
    ) {
        routing.post("/api/messages") {
            try {
                val body = call.receiveText()
                val jsonBody =
                    json.parseToJsonElement(body) as? JsonObject
                        ?: throw IllegalArgumentException("Invalid JSON body")

                val content = jsonBody["content"]?.let { it as? JsonPrimitive }?.content ?: ""
                val sessionId =
                    jsonBody["sessionId"]?.let { it as? JsonPrimitive }?.content
                        ?: UUID.randomUUID().toString()

                val msg =
                    IncomingMessage(
                        id = UUID.randomUUID(),
                        channel = WebChannel.CHANNEL_NAME,
                        userId = "web-user",
                        senderId = "browser",
                        content = content,
                        attachments = emptyList(),
                        threadId = null,
                        metadata = JsonObject(emptyMap()),
                        receivedAt = Clock.System.now(),
                        isInternal = false,
                        isAgentBroadcast = false,
                        sessionId = sessionId,
                    )

                channel.emitMessage(msg)

                call.respondText(
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
                        put("turnId", JsonPrimitive(msg.id.toString()))
                    }.toString(),
                    contentType = ContentType.Application.Json,
                )
            } catch (e: IllegalArgumentException) {
                logger.error("invalid request body", e)
                call.respondText(
                    buildJsonObject {
                        put("error", JsonPrimitive(e.message ?: "Invalid request"))
                    }.toString(),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                logger.error("failed to process message", e)
                call.respondText(
                    buildJsonObject {
                        put("error", JsonPrimitive(e.message ?: "Unknown error"))
                    }.toString(),
                    status = HttpStatusCode.InternalServerError,
                    contentType = ContentType.Application.Json,
                )
            }
        }

        routing.get("/api/sessions/{sessionId}/events") {
            val rawSessionId = call.parameters["sessionId"]
            if (rawSessionId == null) {
                call.respondText(
                    buildJsonObject {
                        put("error", JsonPrimitive("sessionId required"))
                    }.toString(),
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                )
                return@get
            }

            val session = channel.getOrCreateSession(rawSessionId)

            val lastEventId =
                call.request.headers["Last-Event-ID"]?.toLongOrNull()
                    ?: call.request.queryParameters["lastEventId"]?.toLongOrNull()
                    ?: 0L

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                // Replay buffered events for reconnecting clients. Record the high-water mark so
                // live events that arrive in the window between replay and subscription are
                // deduplicated rather than sent twice or missed.
                val replayHighWaterMark =
                    if (lastEventId > 0) {
                        val buffered = session.getEventsSince(lastEventId)
                        buffered.forEach { event ->
                            write(formatSseEvent(event))
                            flush()
                        }
                        buffered.maxOfOrNull { it.id } ?: lastEventId
                    } else {
                        0L
                    }

                session.collectEvents { event ->
                    if (event.id > replayHighWaterMark) {
                        write(formatSseEvent(event))
                        flush()
                    }
                }
            }
        }
    }

    private fun formatSseEvent(event: WebSseEvent): String =
        buildString {
            append("id: ${event.id}\n")
            append("event: ${event.type}\n")
            append("data: ")
            append(buildSseData(event))
            append("\n\n")
        }

    private fun buildSseData(event: WebSseEvent): String =
        buildJsonObject {
            event.text?.let { put("text", JsonPrimitive(it)) }
            event.approvalId?.let { put("approvalId", JsonPrimitive(it)) }
            event.approvalTool?.let { put("approvalTool", JsonPrimitive(it)) }
            event.tool?.let { put("tool", JsonPrimitive(it)) }
            event.expiresAt?.let { put("expiresAt", JsonPrimitive(it)) }
            event.input?.let { put("input", JsonPrimitive(it)) }
            event.output?.let { put("output", JsonPrimitive(it)) }
            event.message?.let { put("message", JsonPrimitive(it)) }
            event.retriable?.let { put("retriable", JsonPrimitive(it)) }
        }.toString()
}
