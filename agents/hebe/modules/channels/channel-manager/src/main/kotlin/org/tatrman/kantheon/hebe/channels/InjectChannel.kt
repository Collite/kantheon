package org.tatrman.kantheon.hebe.channels

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class InjectChannel(
    private val capacity: Int = DEFAULT_CAPACITY,
) : org.tatrman.kantheon.hebe.api.Channel {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val inputChannel = Channel<IncomingMessage>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val pendingCount = AtomicInteger(0)
    private val sharedFlow =
        MutableSharedFlow<IncomingMessage>(
            extraBufferCapacity = capacity,
            replay = 0,
        )

    val flow: Flow<IncomingMessage> = sharedFlow

    suspend fun send(msg: IncomingMessage): Boolean =
        try {
            if (pendingCount.get() >= capacity) {
                logger.warn(
                    "inject channel buffer full (capacity={}), oldest message will be dropped: id={}",
                    capacity,
                    msg.id,
                )
            }
            inputChannel.send(msg)
            pendingCount.incrementAndGet()
            true
        } catch (e: ClosedSendChannelException) {
            logger.error("failed to send message to inject channel (channel closed): id={}", msg.id, e)
            false
        }

    override val name: String = "inject"

    override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> {
        scope.launch {
            for (msg in inputChannel) {
                pendingCount.decrementAndGet()
                sharedFlow.emit(msg)
            }
        }
        return sharedFlow
    }

    override suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ) {
        logger.debug("inject channel reply called (no-op): incomingId={}", ctx.incomingId)
    }

    override fun supportsDraftUpdates(): Boolean = false

    override suspend fun updateDraft(
        ctx: ReplyContext,
        partial: String,
    ) {
        logger.debug("inject channel updateDraft called (no-op): incomingId={}", ctx.incomingId)
    }

    override suspend fun broadcast(
        userId: String,
        msg: OutboundMessage,
    ) {
        logger.debug("inject channel broadcast called (no-op): userId={}", userId)
    }

    override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

    override suspend fun shutdown() {
        logger.info("shutting down inject channel")
        inputChannel.close()
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
