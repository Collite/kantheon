package org.tatrman.kantheon.hebe.channels.telegram

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.client.jetty.JettyTelegramClient
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.message.Message

class TelegramChannelTest {
    private val operatorTelegramId = 123456789L

    private fun makeChannel(
        operatorId: Long = operatorTelegramId,
        mockClient: JettyTelegramClient? = null,
    ): TelegramChannel =
        TelegramChannel(
            botToken = "test-token",
            operatorTelegramId = operatorId,
            mode = TelegramChannel.TelegramMode.WEBHOOK,
            clientFactory = { mockClient ?: mockk(relaxed = true) },
        )

    private fun makeUpdate(fromId: Long): Update {
        val user = mockk<User> { every { id } returns fromId }
        val chat = mockk<Chat> { every { id } returns 999L }
        val message =
            mockk<Message> {
                every { from } returns user
                every { this@mockk.chat } returns chat
                every { text } returns "hello"
                every { caption } returns null
            }
        return mockk<Update> {
            every { hasMessage() } returns true
            every { this@mockk.message } returns message
        }
    }

    private fun makeReplyContext(sessionId: String = "123456789") = ReplyContext(incomingId = UUID.randomUUID(), sessionId = sessionId)

    @Test
    fun `name is telegram`() {
        assertEquals("telegram", makeChannel().name)
    }

    @Test
    fun `supportsDraftUpdates returns true`() {
        assertTrue(makeChannel().supportsDraftUpdates())
    }

    @Test
    fun `healthCheck returns Down when not started`() =
        runTest {
            assertEquals(ChannelHealth.Down, makeChannel().healthCheck())
        }

    @Test
    fun `operator gate allows message from correct user id`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = makeChannel()
            val flow = channel.start(backgroundScope)

            val resultDeferred = backgroundScope.async { flow.first() }
            channel.consume(listOf(makeUpdate(fromId = operatorTelegramId)))

            val received = withTimeoutOrNull(500) { resultDeferred.await() }
            assertNotNull(received)
            assertEquals("hello", received?.content)
        }

    @Test
    fun `operator gate drops message from unauthorized user id`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = makeChannel()
            val flow = channel.start(backgroundScope)

            val resultDeferred = backgroundScope.async { flow.first() }
            channel.consume(listOf(makeUpdate(fromId = 999999L)))

            val received = withTimeoutOrNull(200) { resultDeferred.await() }
            assertNull(received)
        }

    @Test
    fun `healthCheck returns Up after start when getMe succeeds`() =
        runTest(UnconfinedTestDispatcher()) {
            val mockClient = mockk<JettyTelegramClient>(relaxed = true)
            every { mockClient.execute(any<GetMe>()) } returns mockk(relaxed = true)
            val channel = makeChannel(mockClient = mockClient)
            channel.start(backgroundScope)
            assertEquals(ChannelHealth.Up, channel.healthCheck())
        }

    @Test
    fun `healthCheck returns Down when getMe throws`() =
        runTest(UnconfinedTestDispatcher()) {
            val mockClient = mockk<JettyTelegramClient>(relaxed = true)
            every { mockClient.execute(any<GetMe>()) } throws
                org.telegram.telegrambots.meta.exceptions
                    .TelegramApiException("network error")
            val channel = makeChannel(mockClient = mockClient)
            channel.start(backgroundScope)
            assertEquals(ChannelHealth.Down, channel.healthCheck())
        }

    @Test
    fun `reply sends message to correct chat id`() =
        runTest {
            val mockClient = mockk<JettyTelegramClient>(relaxed = true)
            val sentMessage =
                mockk<Message>(relaxed = true) {
                    every { messageId } returns 100
                    every { chat } returns mockk(relaxed = true) { every { id } returns 999L }
                }
            every { mockClient.execute(any<SendMessage>()) } returns sentMessage
            val channel = makeChannel(mockClient = mockClient)
            channel.start(backgroundScope)

            channel.reply(makeReplyContext(sessionId = "999"), OutboundMessage("Hello agent!"))

            verify { mockClient.execute(match<SendMessage> { it.chatId == "999" && it.text == "Hello agent!" }) }
        }

    @Test
    fun `reply sends text unchanged without escaping`() =
        runTest {
            val mockClient = mockk<JettyTelegramClient>(relaxed = true)
            val sentMessage =
                mockk<Message>(relaxed = true) {
                    every { messageId } returns 101
                    every { chat } returns mockk(relaxed = true) { every { id } returns 999L }
                }
            every { mockClient.execute(any<SendMessage>()) } returns sentMessage
            val channel = makeChannel(mockClient = mockClient)
            channel.start(backgroundScope)

            val textWithFormatting = "**bold** _italic_ `code` [link](url)"
            channel.reply(makeReplyContext(sessionId = "999"), OutboundMessage(textWithFormatting))

            verify { mockClient.execute(match<SendMessage> { it.text == textWithFormatting }) }
        }

    @Test
    fun `reply does not throw when client is null`() =
        runTest {
            makeChannel().reply(makeReplyContext(), OutboundMessage("Hello!"))
        }

    @Test
    fun `broadcast does not throw`() =
        runTest {
            makeChannel().broadcast("user1", OutboundMessage("Hello!"))
        }
}
