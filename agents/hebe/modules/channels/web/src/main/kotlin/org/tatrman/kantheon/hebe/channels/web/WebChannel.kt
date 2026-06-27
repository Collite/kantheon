package org.tatrman.kantheon.hebe.channels.web

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory

class WebChannel : org.tatrman.kantheon.hebe.api.Channel {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val sessionManager = WebSessionManager()
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: Flow<IncomingMessage> = _incomingMessages

    override val name: String = CHANNEL_NAME

    override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = _incomingMessages

    fun getOrCreateSession(sessionId: String): WebSession = sessionManager.getOrCreate(sessionId)

    fun getSession(sessionId: String): WebSession? = sessionManager.get(sessionId)

    suspend fun emitMessage(msg: IncomingMessage) {
        _incomingMessages.emit(msg)
    }

    fun removeSession(sessionId: String) {
        sessionManager.remove(sessionId)
    }

    override suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ) {
        val sessionId = ctx.sessionId
        val session = sessionManager.get(sessionId) ?: return
        session.emit(WebSseEvent.done(msg.text, msg.approvalRequest?.id, msg.approvalRequest?.tool))
    }

    override fun supportsDraftUpdates(): Boolean = true

    override suspend fun updateDraft(
        ctx: ReplyContext,
        partial: String,
    ) {
        val sessionId = ctx.sessionId
        val session = sessionManager.get(sessionId) ?: return
        session.emit(WebSseEvent.textDelta(partial))
    }

    override suspend fun broadcast(
        userId: String,
        msg: OutboundMessage,
    ) {
        logger.debug("broadcast called on web channel: userId={}", userId)
    }

    override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

    override suspend fun shutdown() {
        logger.info("shutting down web channel")
        sessionManager.closeAll()
    }

    companion object {
        const val CHANNEL_NAME = "web"
    }
}

class WebSessionManager {
    private val sessions = ConcurrentHashMap<String, WebSession>()

    fun getOrCreate(sessionId: String): WebSession = sessions.getOrPut(sessionId) { WebSession(sessionId) }

    fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun get(sessionId: String): WebSession? = sessions[sessionId]

    fun closeAll() {
        sessions.clear()
    }
}

class WebSession(
    val sessionId: String,
) {
    private val _events = MutableSharedFlow<WebSseEvent>(extraBufferCapacity = 64)
    val events: Flow<WebSseEvent> = _events

    private val eventBuffer = ArrayDeque<WebSseEvent>(RING_BUFFER_SIZE)
    private var lastEventId = 0L

    suspend fun emit(event: WebSseEvent) {
        lastEventId++
        val withId = event.copy(id = lastEventId)
        eventBuffer.addLast(withId)
        if (eventBuffer.size > RING_BUFFER_SIZE) {
            eventBuffer.removeFirst()
        }
        _events.emit(withId)
    }

    fun getEventsSince(lastId: Long): List<WebSseEvent> = eventBuffer.filter { it.id > lastId }.toList()

    suspend fun collectEvents(onEvent: suspend (WebSseEvent) -> Unit) {
        _events.collect { event ->
            onEvent(event)
        }
    }

    companion object {
        const val RING_BUFFER_SIZE = 32
    }
}

data class WebSseEvent(
    val type: String,
    val text: String? = null,
    val approvalId: String? = null,
    val approvalTool: String? = null,
    val tool: String? = null,
    val expiresAt: String? = null,
    val input: Int? = null,
    val output: Int? = null,
    val message: String? = null,
    val retriable: Boolean? = null,
    val id: Long = 0,
) {
    companion object {
        fun textDelta(
            text: String,
            id: Long = 0,
        ) = WebSseEvent(type = "text_delta", text = text, id = id)

        fun done(
            text: String,
            approvalId: String? = null,
            approvalTool: String? = null,
            id: Long = 0,
        ) = WebSseEvent(type = "done", text = text, approvalId = approvalId, approvalTool = approvalTool, id = id)

        fun approvalRequested(
            id: String,
            tool: String,
            expiresAt: String,
            eventId: Long = 0,
        ) = WebSseEvent(type = "approval_requested", approvalId = id, tool = tool, expiresAt = expiresAt, id = eventId)

        fun tokenUsage(
            input: Int,
            output: Int,
            id: Long = 0,
        ) = WebSseEvent(type = "token_usage", input = input, output = output, id = id)

        fun error(
            message: String,
            retriable: Boolean,
            id: Long = 0,
        ) = WebSseEvent(type = "error", message = message, retriable = retriable, id = id)
    }
}
