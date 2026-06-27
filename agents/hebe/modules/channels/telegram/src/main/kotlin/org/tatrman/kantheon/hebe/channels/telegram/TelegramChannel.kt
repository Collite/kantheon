package org.tatrman.kantheon.hebe.channels.telegram

import org.tatrman.kantheon.hebe.api.Channel
import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.IncomingMessage
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.jetty.JettyTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.exceptions.TelegramApiException

@Suppress("TooGenericExceptionCaught")
class TelegramChannel(
    private val botToken: String,
    private val operatorTelegramId: Long,
    private val mode: TelegramMode = TelegramMode.LONG_POLLING,
    private val clientFactory: (String) -> JettyTelegramClient = { JettyTelegramClient(it) },
    // P2 Stage 2.3 T5 — when non-null (keycloak profile), inbound admission is by
    // chat-id identity mapping instead of the [operatorTelegramId] allowlist.
    private val identityCheck: ((chatId: String) -> Boolean)? = null,
) : Channel,
    LongPollingUpdateConsumer {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    private val sentMessages = ConcurrentHashMap<Int, Long>()
    private val draftThrottler = DraftThrottler()
    private val mutex = Mutex()
    private var isRunning = false
    private var telegramClient: JettyTelegramClient? = null
    private var botSession: org.telegram.telegrambots.longpolling.BotSession? = null
    private var channelScope: CoroutineScope? = null

    override val name: String = CHANNEL_NAME

    override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> {
        logger.info("starting telegram channel for operator {} in {} mode", operatorTelegramId, mode)
        channelScope = scope

        telegramClient = clientFactory(botToken)

        when (mode) {
            TelegramMode.LONG_POLLING -> startLongPolling()
            TelegramMode.WEBHOOK -> startWebhook()
        }

        return incomingMessages
    }

    private fun startLongPolling() {
        try {
            val api = TelegramBotsLongPollingApplication()
            botSession = api.registerBot(botToken, this)
            isRunning = true
            logger.info("telegram bot registered successfully (long-polling mode)")
        } catch (e: Exception) {
            logger.error("failed to register telegram bot for long-polling", e)
        }
    }

    private fun startWebhook() {
        isRunning = true
        logger.info("telegram channel ready in webhook mode")
    }

    @Suppress("NestedBlockDepth")
    override suspend fun reply(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ) {
        val chatId = ctx.sessionId
        if (chatId.isNullOrEmpty()) {
            logger.warn("reply called with empty sessionId")
            return
        }

        val client =
            telegramClient ?: run {
                logger.error("telegram client not initialized")
                return
            }

        try {
            val approval = msg.approvalRequest
            if (approval != null) {
                val approvalText = buildApprovalText(ctx, msg)
                val sentMessage = sendApprovalMessage(client, chatId.toLong(), approvalText)
                if (sentMessage != null) {
                    sentMessages[sentMessage.messageId] = sentMessage.chat.id
                }
            } else {
                val finalText = msg.text
                if (finalText.isNotEmpty()) {
                    val sentMessage = sendMessage(client, chatId.toLong(), finalText)
                    if (sentMessage != null) {
                        sentMessages[sentMessage.messageId] = sentMessage.chat.id
                    }
                }
            }
        } catch (e: TelegramApiException) {
            logger.error("failed to send reply to chat {}", chatId, e)
        }
    }

    private fun buildApprovalText(
        ctx: ReplyContext,
        msg: OutboundMessage,
    ): String {
        val approval = msg.approvalRequest!!
        return buildString {
            append("⚠️ *Approval Required*\n\n")
            append("Tool: ${approval.tool}\n")
            append("Session: ${ctx.sessionId}\n")
            if (msg.text.isNotEmpty()) {
                append("\n${msg.text}")
            }
        }
    }

    override fun supportsDraftUpdates(): Boolean = true

    override suspend fun updateDraft(
        ctx: ReplyContext,
        partial: String,
    ) {
        val chatId = ctx.sessionId ?: return
        val telegramChatId = chatId.toLongOrNull() ?: return

        val messageEntry = sentMessages.entries.find { it.value == telegramChatId }
        val messageId = messageEntry?.key ?: return

        val client = telegramClient ?: return

        channelScope?.let { scope ->
            draftThrottler.throttle(scope, messageId, telegramChatId, partial) { actualChatId, actualMessageId, text ->
                try {
                    editMessageText(client, actualChatId, actualMessageId, text)
                } catch (e: TelegramApiException) {
                    logger.warn("failed to edit message: {}", e.message)
                    null
                }
            }
        }
    }

    override suspend fun broadcast(
        userId: String,
        msg: OutboundMessage,
    ) {
        logger.debug("broadcast called on telegram channel: userId={}", userId)
    }

    override suspend fun healthCheck(): ChannelHealth {
        if (!isRunning) return ChannelHealth.Down
        val client = telegramClient ?: return ChannelHealth.Down
        return try {
            client.execute(GetMe())
            ChannelHealth.Up
        } catch (e: Exception) {
            logger.warn("telegram getMe health check failed", e)
            ChannelHealth.Down
        }
    }

    override suspend fun shutdown() {
        logger.info("shutting down telegram channel")
        draftThrottler.flushAll()
        try {
            botSession?.close()
        } catch (e: Exception) {
            logger.warn("error closing bot session", e)
        }
        isRunning = false
    }

    override fun consume(updates: List<Update>) {
        updates.forEach { update ->
            processUpdate(update)
        }
    }

    private fun processUpdate(update: Update) {
        if (!update.hasMessage()) return

        val message = update.message
        val user = message.from
        val chatId: String = message.chat.id.toString()

        // Admission (P2 Stage 2.3 T5): on a keycloak profile the chat must map to
        // a known Keycloak user; otherwise fall back to the operator allowlist.
        val guard = identityCheck
        if (guard != null) {
            if (!guard(chatId)) {
                logger.info("ignored message from unmapped chat {} (no chat_user_map entry)", chatId)
                return
            }
        } else if (user.id != operatorTelegramId) {
            logger.info("ignored message from unauthorized user {} (expected {})", user.id, operatorTelegramId)
            return
        }

        val content: String = message.text ?: message.caption ?: ""

        if (content.isEmpty()) {
            logger.debug("ignored empty message from user {}", user.id)
            return
        }

        val incomingMessage =
            IncomingMessage(
                id = UUID.randomUUID(),
                channel = CHANNEL_NAME,
                userId = user.id.toString(),
                senderId = user.id.toString(),
                content = content,
                attachments = emptyList(),
                threadId = null,
                metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
                receivedAt = Clock.System.now(),
                isInternal = false,
                isAgentBroadcast = false,
                sessionId = chatId,
            )

        channelScope?.launch {
            incomingMessages.emit(incomingMessage)
        } ?: run {
            logger.error("channel scope not initialized, cannot emit message")
        }
    }

    private fun sendMessage(
        client: JettyTelegramClient,
        chatId: Long,
        text: String,
    ): Message? =
        try {
            client.execute(
                SendMessage
                    .builder()
                    .chatId(chatId)
                    .text(text)
                    .build(),
            )
        } catch (e: TelegramApiException) {
            logger.error("failed to send message to chat {}", chatId, e)
            null
        }

    private fun sendApprovalMessage(
        client: JettyTelegramClient,
        chatId: Long,
        text: String,
    ): Message? =
        try {
            client.execute(
                SendMessage
                    .builder()
                    .chatId(chatId)
                    .text(text)
                    .build(),
            )
        } catch (e: TelegramApiException) {
            logger.error("failed to send approval message to chat {}", chatId, e)
            null
        }

    @Suppress("SwallowedException", "UnusedParameter", "ReturnTypeMismatch")
    private fun editMessageText(
        client: JettyTelegramClient,
        chatId: Long,
        messageId: Int,
        text: String,
    ): Message? =
        try {
            @Suppress("UNCHECKED_CAST")
            val result =
                client.execute(
                    EditMessageText
                        .builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .build(),
                )
            result as? Message
        } catch (e: TelegramApiException) {
            logger.warn("failed to edit message: chatId={}, messageId={}, error={}", chatId, messageId, e.message)
            null
        }

    companion object {
        const val CHANNEL_NAME = "telegram"
    }

    enum class TelegramMode {
        LONG_POLLING,
        WEBHOOK,
    }
}

interface TelegramUpdateConsumer {
    fun consume(updates: List<Update>)
}
