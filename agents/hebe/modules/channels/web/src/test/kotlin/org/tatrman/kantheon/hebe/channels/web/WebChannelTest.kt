package org.tatrman.kantheon.hebe.channels.web

import org.tatrman.kantheon.hebe.api.ChannelHealth
import org.tatrman.kantheon.hebe.api.OutboundMessage
import org.tatrman.kantheon.hebe.api.ReplyContext
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebChannelTest {
    private lateinit var webChannel: WebChannel

    private fun makeReplyContext(sessionId: String) = ReplyContext(incomingId = UUID.randomUUID(), sessionId = sessionId, threadId = null)

    @BeforeEach
    fun setup() {
        webChannel = WebChannel()
    }

    @Test
    fun `name is web`() {
        assertEquals("web", webChannel.name)
    }

    @Test
    fun `supportsDraftUpdates returns true`() {
        assertTrue(webChannel.supportsDraftUpdates())
    }

    @Test
    fun `healthCheck returns Up`() =
        runTest {
            assertEquals(ChannelHealth.Up, webChannel.healthCheck())
        }

    @Test
    fun `start returns the incoming messages flow`() =
        runTest {
            val flow = webChannel.start(backgroundScope)
            assertNotNull(flow)
        }

    @Test
    fun `emitMessage arrives on the flow returned by start`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = webChannel.start(backgroundScope)
            val resultDeferred = backgroundScope.async { flow.first() }

            val msg =
                org.tatrman.kantheon.hebe.api.IncomingMessage(
                    id = UUID.randomUUID(),
                    channel = "web",
                    userId = "user",
                    senderId = "browser",
                    content = "test message",
                    attachments = emptyList(),
                    threadId = null,
                    metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
                    receivedAt =
                        kotlin.time.Clock.System
                            .now(),
                )

            webChannel.emitMessage(msg)

            val received = withTimeout(500) { resultDeferred.await() }
            assertEquals("test message", received.content)
        }

    @Test
    fun `reply emits done SSE event to the correct session`() =
        runTest {
            val sessionId = "reply-session"
            val session = webChannel.getOrCreateSession(sessionId)

            webChannel.reply(
                makeReplyContext(sessionId),
                OutboundMessage("Agent response"),
            )

            val events = session.getEventsSince(0)
            assertEquals(1, events.size)
            assertEquals("done", events[0].type)
            assertEquals("Agent response", events[0].text)
        }

    @Test
    fun `updateDraft emits text_delta SSE event to the correct session`() =
        runTest {
            val sessionId = "draft-session"
            val session = webChannel.getOrCreateSession(sessionId)

            webChannel.updateDraft(makeReplyContext(sessionId), "streaming partial...")

            val events = session.getEventsSince(0)
            assertEquals(1, events.size)
            assertEquals("text_delta", events[0].type)
            assertEquals("streaming partial...", events[0].text)
        }

    @Test
    fun `reply to unknown session is silently ignored`() =
        runTest {
            webChannel.reply(makeReplyContext("no-such-session"), OutboundMessage("ignored"))
        }

    @Test
    fun `getOrCreateSession creates new session`() {
        val session = webChannel.getOrCreateSession("new-session")
        assertNotNull(session)
        assertEquals("new-session", session.sessionId)
    }

    @Test
    fun `getOrCreateSession returns existing session`() {
        val s1 = webChannel.getOrCreateSession("shared")
        val s2 = webChannel.getOrCreateSession("shared")
        assertEquals(s1, s2)
    }

    @Test
    fun `removeSession removes session`() {
        webChannel.getOrCreateSession("to-remove")
        webChannel.removeSession("to-remove")
        assertNull(webChannel.getSession("to-remove"))
    }

    @Test
    fun `getSession returns null for unknown session`() {
        assertNull(webChannel.getSession("non-existent"))
    }

    @Test
    fun `broadcast does not throw`() =
        runTest {
            webChannel.broadcast("user1", OutboundMessage("Hello!"))
        }
}
