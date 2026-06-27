package org.tatrman.kantheon.hebe.channels.web

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebSessionTest {
    private lateinit var session: WebSession

    @BeforeEach
    fun setup() {
        session = WebSession("test-session")
    }

    @Test
    fun `sessionId is set correctly`() {
        assertEquals("test-session", session.sessionId)
    }

    @Test
    fun `getEventsSince returns empty list for new session`() {
        assertTrue(session.getEventsSince(0).isEmpty())
    }

    @Test
    fun `emit increments event id and stores in buffer`() =
        runTest {
            val event = WebSseEvent.textDelta("first")
            session.emit(event)

            val buffered = session.getEventsSince(0)
            assertEquals(1, buffered.size)
            assertEquals(1L, buffered[0].id)
            assertEquals("first", buffered[0].text)
        }

    @Test
    fun `getEventsSince returns only events after given id`() =
        runTest {
            session.emit(WebSseEvent.textDelta("a"))
            session.emit(WebSseEvent.textDelta("b"))
            session.emit(WebSseEvent.textDelta("c"))

            val after1 = session.getEventsSince(1)
            assertEquals(2, after1.size)
            assertEquals("b", after1[0].text)
            assertEquals("c", after1[1].text)
        }

    @Test
    fun `ring buffer keeps only last 32 events`() =
        runTest {
            repeat(WebSession.RING_BUFFER_SIZE + 5) { i ->
                session.emit(WebSseEvent.textDelta("msg-$i"))
            }
            val all = session.getEventsSince(0)
            assertEquals(WebSession.RING_BUFFER_SIZE, all.size)
            assertEquals("msg-5", all.first().text)
            assertEquals("msg-${WebSession.RING_BUFFER_SIZE + 4}", all.last().text)
        }

    @Test
    fun `RING_BUFFER_SIZE is 32`() {
        assertEquals(32, WebSession.RING_BUFFER_SIZE)
    }

    @Test
    fun `WebSseEvent textDelta factory`() {
        val event = WebSseEvent.textDelta("hello")
        assertEquals("text_delta", event.type)
        assertEquals("hello", event.text)
    }

    @Test
    fun `WebSseEvent done factory`() {
        val event = WebSseEvent.done("response", approvalId = "a-123", approvalTool = "shell")
        assertEquals("done", event.type)
        assertEquals("response", event.text)
        assertEquals("a-123", event.approvalId)
        assertEquals("shell", event.approvalTool)
    }

    @Test
    fun `WebSseEvent approvalRequested factory`() {
        val event = WebSseEvent.approvalRequested("a-456", "browser_open", "2024-01-01T00:00:00Z")
        assertEquals("approval_requested", event.type)
        assertEquals("a-456", event.approvalId)
        assertEquals("browser_open", event.tool)
    }

    @Test
    fun `WebSseEvent tokenUsage factory`() {
        val event = WebSseEvent.tokenUsage(input = 100, output = 50)
        assertEquals("token_usage", event.type)
        assertEquals(100, event.input)
        assertEquals(50, event.output)
    }

    @Test
    fun `WebSseEvent error factory`() {
        val event = WebSseEvent.error(message = "something went wrong", retriable = true)
        assertEquals("error", event.type)
        assertEquals("something went wrong", event.message)
        assertTrue(event.retriable!!)
    }
}
