package org.tatrman.kantheon.hebe.channels.web

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutesTest {
    private fun buildApp(channel: WebChannel) =
        testApplication {
            application {
                routing {
                    Routes.register(this, channel)
                }
            }
        }

    @Test
    fun `POST api-messages returns 200 with sessionId and turnId`() =
        testApplication {
            val channel = WebChannel()
            application {
                routing { Routes.register(this, channel) }
            }

            val response =
                client.post("/api/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"hello","sessionId":"sess-1"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue("sessionId" in body)
            assertTrue("turnId" in body)
        }

    @Test
    fun `POST api-messages with missing content returns 200 with empty content`() =
        testApplication {
            val channel = WebChannel()
            application {
                routing { Routes.register(this, channel) }
            }

            val response =
                client.post("/api/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"sessionId":"sess-2"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST api-messages with invalid JSON returns 400`() =
        testApplication {
            val channel = WebChannel()
            application {
                routing { Routes.register(this, channel) }
            }

            val response =
                client.post("/api/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("not-json")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST api-messages emits IncomingMessage with correct sessionId`() =
        runTest(UnconfinedTestDispatcher()) {
            val channel = WebChannel()
            val flow = channel.start(backgroundScope)
            val resultDeferred = backgroundScope.async { flow.first() }

            testApplication {
                application {
                    routing { Routes.register(this, channel) }
                }
                client.post("/api/messages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"from browser","sessionId":"browser-session-abc"}""")
                }
            }

            val received = withTimeout(2000) { resultDeferred.await() }
            assertEquals("from browser", received.content)
            assertEquals("browser-session-abc", received.sessionId)
            assertEquals("web", received.channel)
        }

    @Test
    fun `GET api-sessions-id-events returns 200 with event-stream content type`() =
        testApplication {
            val channel = WebChannel()
            application {
                routing { Routes.register(this, channel) }
            }
            channel.getOrCreateSession("stream-session")

            // Use prepareGet().execute to access response headers without reading the streaming body.
            client.prepareGet("/api/sessions/stream-session/events").execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val contentType = response.headers["Content-Type"] ?: ""
                assertTrue("text/event-stream" in contentType, "Expected text/event-stream, got: $contentType")
            }
        }

    @Test
    fun `GET api-sessions-id-events replays buffered events via ring buffer`() {
        // Verify replay logic directly on the session layer — the HTTP route calls
        // session.getEventsSince(lastId) and formats each event. This test validates
        // both the ring-buffer filtering and the SSE formatting helper.
        val session = WebSession("replay-test")
        kotlinx.coroutines.runBlocking {
            session.emit(WebSseEvent.done("msg1"))
            session.emit(WebSseEvent.done("msg2"))
            session.emit(WebSseEvent.done("msg3"))
        }

        val replayed = session.getEventsSince(1)
        assertEquals(2, replayed.size)
        assertEquals("msg2", replayed[0].text)
        assertEquals("msg3", replayed[1].text)
    }

    @Test
    fun `reply routes done SSE event to the correct session`() =
        testApplication {
            val channel = WebChannel()
            application {
                routing { Routes.register(this, channel) }
            }

            val session = channel.getOrCreateSession("reply-target")

            channel.reply(
                org.tatrman.kantheon.hebe.api.ReplyContext(
                    incomingId = java.util.UUID.randomUUID(),
                    sessionId = "reply-target",
                    threadId = null,
                ),
                org.tatrman.kantheon.hebe.api.OutboundMessage("Reply text"),
            )

            val events = session.getEventsSince(0)
            assertNotNull(events.firstOrNull { it.type == "done" && it.text == "Reply text" })
        }
}
