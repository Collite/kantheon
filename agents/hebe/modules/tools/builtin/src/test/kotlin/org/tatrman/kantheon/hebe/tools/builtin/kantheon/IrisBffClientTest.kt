package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * IrisBffClient unit spec (P4 S4.1 T1/T5) against a MockEngine'd iris-bff: session
 * create, turn POST (OBO bearer + origin=SCHEDULED + origin_ref), SSE consumption,
 * and the terminal status mapping (done / clarification / error). The live iris-bff
 * run is gated on Iris ≥ Phase 2 (planning-conventions §4).
 */
class IrisBffClientTest {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()

    private fun sse(vararg events: IrisStreamEvent): String =
        buildString { events.forEach { append("data: ").append(printer.print(it)).append("\n\n") } }

    private fun event(block: IrisStreamEvent.Builder.() -> Unit) =
        IrisStreamEvent
            .newBuilder()
            .setTurnId("turn-1")
            .apply(block)
            .build()

    private fun client(
        sseBody: String,
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ): IrisBffClient {
        val engine =
            MockEngine { request ->
                captured.add(request)
                when {
                    request.url.encodedPath.endsWith("/v1/sessions") ->
                        respond(
                            """{"sessionRef":"sess-1"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    else ->
                        respond(
                            ByteReadChannel(sseBody),
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                }
            }
        return IrisBffClient(
            baseUrl = "http://iris-bff",
            bearer = { "obo-token-xyz" },
            httpClient = HttpClient(engine),
        )
    }

    @Test
    fun `createSession returns the session ref`() =
        runBlocking {
            assertEquals("sess-1", client("").createSession("⏰ Weekly revenue"))
        }

    @Test
    fun `turn POST carries the OBO bearer, origin SCHEDULED and origin_ref`() =
        runBlocking {
            val captured = mutableListOf<HttpRequestData>()
            val done = sse(event { setDone(DoneEvent.newBuilder().setOutcome("done")) })
            client(done, captured).runTurn("sess-1", "How did revenue trend?", originRef = "routine-7")

            val turn = captured.single { it.url.encodedPath.endsWith("/v1/chat/stream") }
            assertEquals("Bearer obo-token-xyz", turn.headers[HttpHeaders.Authorization])
            val body = (turn.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"origin\":\"SCHEDULED\""), body)
            assertTrue(body.contains("\"originRef\":\"routine-7\""), body)
        }

    @Test
    fun `done outcome maps to Succeeded with the terminal envelope`() =
        runBlocking {
            val stream =
                sse(
                    event { setEnvelope(FormatEnvelope.getDefaultInstance()) },
                    event { setDone(DoneEvent.newBuilder().setOutcome("done")) },
                )
            val result = client(stream).runTurn("sess-1", "q", "r1")
            val ok = assertInstanceOf(IrisTurnResult.Succeeded::class.java, result)
            assertEquals("turn-1", ok.turnRef)
        }

    @Test
    fun `clarification outcome maps to AwaitingAgent`() =
        runBlocking {
            val stream = sse(event { setDone(DoneEvent.newBuilder().setOutcome("clarification")) })
            assertInstanceOf(IrisTurnResult.AwaitingAgent::class.java, client(stream).runTurn("sess-1", "q", "r1"))
        }

    @Test
    fun `an unknown terminal outcome maps to Failed, not Succeeded`() =
        runBlocking {
            val stream = sse(event { setDone(DoneEvent.newBuilder().setOutcome("cancelled")) })
            assertInstanceOf(IrisTurnResult.Failed::class.java, client(stream).runTurn("sess-1", "q", "r1"))
        }

    @Test
    fun `error event maps to Failed`() =
        runBlocking {
            val stream = sse(event { setError(ErrorEvent.newBuilder().setCode("BOOM").setMessage("dispatch failed")) })
            val result = client(stream).runTurn("sess-1", "q", "r1")
            val failed = assertInstanceOf(IrisTurnResult.Failed::class.java, result)
            assertTrue(failed.error.contains("BOOM"), failed.error)
        }

    @Test
    fun `a stream with no terminal event fails rather than hanging`() =
        runBlocking {
            val stream = sse(event { setEnvelope(FormatEnvelope.getDefaultInstance()) })
            assertInstanceOf(IrisTurnResult.Failed::class.java, client(stream).runTurn("sess-1", "q", "r1"))
        }
}
