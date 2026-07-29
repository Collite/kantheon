package org.tatrman.kantheon.pythia.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import io.ktor.server.application.install as installServer
import kotlin.time.Duration.Companion.seconds

/**
 * ST-P1·S2·T1 — pythia's exposure to Ktor Netty's `responseWriteTimeoutSeconds`.
 *
 * Pythia is one of the estate's three `KtorEngine.NETTY` services, and it is the only one
 * that serves SSE through Ktor's **`sse { }` plugin** rather than the hand-rolled
 * `respondTextWriter` that iris-bff and golem use. Its commit semantics are therefore the
 * plugin's, not ours, and the ST blast-radius table listed it as *unproven*.
 *
 * Reading the handler ([sseRoutes]) says the risk is low: it authenticates, loads the
 * record, replays the PG log and returns — there is no long-running work before the first
 * `send`, and the NATS live-tail that could idle is integration-deferred. But "looks fine"
 * is exactly the reasoning that produced the 2026-07-29 outage, so this measures the
 * property that actually matters and would keep mattering if the handler ever grew slow:
 *
 *   **does the `sse { }` plugin commit the response before the handler produces a frame?**
 *
 * **MEASURED 2026-07-29: it does NOT.** A `sse { }` handler that stays quiet past the
 * engine's 10s cap has its socket closed with no status line — byte-for-byte the iris-bff
 * failure. The plugin buys no protection here, so the preamble discipline applies to it
 * exactly as it does to the hand-rolled helpers.
 *
 * Pythia is not broken *today* only because its handler is fast (authenticate → find →
 * replay → return, with the idle-prone NATS live-tail still integration-deferred). That is
 * a property of the current handler, not of the endpoint, which is why [sseRoutes] now
 * opens with a comment preamble: the fix has to survive the live-tail landing later.
 *
 * The two cases below pin both halves of the rule so neither can regress silently.
 *
 * See `project/server/features/stream-timeouts/`.
 */
class SsePluginWriteTimeoutSpec :
    StringSpec({

        "WITHOUT a preamble, a quiet sse{} handler is reaped by the engine — the hazard"
            .config(timeout = 90.seconds) {
                // Past Ktor's 10s Netty `responseWriteTimeoutSeconds` default — the cliff.
                val firstFrameDelayMs = 15_000L

                val server =
                    KtorServerBootstrap.createServer(
                        KtorServerConfig(
                            serviceName = "st-p1-pythia-sse-test",
                            serverPort = 0,
                            engine = KtorEngine.NETTY,
                        ),
                    ) {
                        installServer(SSE)
                        routing {
                            sse("/slow") {
                                // Stands in for a slow admission check / first repository read.
                                delay(firstFrameDelayMs)
                                send(ServerSentEvent(data = """{"ok":true}""", event = "late"))
                            }
                        }
                    }

                server.start(wait = false)
                try {
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port

                    HttpClient(CIO) {
                        install(HttpTimeout) {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.use { client ->
                        // The engine closes the socket before any status line is written, so the
                        // failure surfaces as a read error rather than an HTTP status. This is
                        // exactly what a browser reports as a 502 through a proxy.
                        val reaped =
                            runCatching {
                                client.prepareGet("http://127.0.0.1:$port/slow").execute { response ->
                                    withTimeout(firstFrameDelayMs + 20_000) {
                                        response.bodyAsChannel().readUTF8Line()
                                    }
                                }
                            }.isFailure
                        reaped shouldBe true
                    }
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
                }
            }

        "WITH a comment preamble, the same quiet handler survives — the fix"
            .config(timeout = 90.seconds) {
                val firstFrameDelayMs = 15_000L

                val server =
                    KtorServerBootstrap.createServer(
                        KtorServerConfig(
                            serviceName = "st-p1-pythia-sse-fixed",
                            serverPort = 0,
                            engine = KtorEngine.NETTY,
                        ),
                    ) {
                        installServer(SSE)
                        routing {
                            sse("/slow") {
                                send(ServerSentEvent(comments = "ready"))
                                delay(firstFrameDelayMs)
                                send(ServerSentEvent(data = """{"ok":true}""", event = "late"))
                            }
                        }
                    }

                server.start(wait = false)
                try {
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port

                    HttpClient(CIO) {
                        install(HttpTimeout) {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.use { client ->
                        client.prepareGet("http://127.0.0.1:$port/slow").execute { response ->
                            response.status shouldBe HttpStatusCode.OK
                            val channel = response.bodyAsChannel()

                            // Committed immediately, long before the 10s engine cap.
                            withTimeout(5_000) { channel.readUTF8Line() } shouldBe ": ready"

                            val body =
                                withTimeout(firstFrameDelayMs + 20_000) {
                                    buildString {
                                        while (true) {
                                            val line = channel.readUTF8Line() ?: break
                                            appendLine(line)
                                            if (line.startsWith("data:")) break
                                        }
                                    }
                                }
                            (body.contains("event: late") && body.contains("\"ok\":true")) shouldBe true
                        }
                    }
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
                }
            }
    })
