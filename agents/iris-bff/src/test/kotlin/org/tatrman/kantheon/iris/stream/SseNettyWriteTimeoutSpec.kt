package org.tatrman.kantheon.iris.stream

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import shared.ktor.KtorEngine
import shared.ktor.KtorServerBootstrap
import shared.ktor.KtorServerConfig
import kotlin.time.Duration.Companion.seconds

/**
 * ST-P1·S1·T2 — the regression test for the 2026-07-29 Hartland outage.
 *
 * Unlike [SsePreambleSpec], this stands up a **real Netty socket** through the same
 * [KtorServerBootstrap] production uses, because the defect lives in the engine:
 * `embeddedServer(Netty, ...)` is started with no `configure` block, so Ktor's
 * `responseWriteTimeoutSeconds` keeps its default of 10s and reaps any response that has
 * produced no bytes by then. `testApplication`'s in-memory engine cannot reproduce that.
 *
 * The server deliberately runs the **shipped configuration** — the heartbeat is read from
 * `application.conf` rather than hardcoded — so this test tracks whatever the estate
 * actually deploys. On the pre-fix code (no preamble, `heartbeat-s = 15`) the stream stays
 * silent past the engine's 10s cap and the client sees the socket close with no status
 * line, which is precisely what users saw as `HTTP 502`.
 *
 * Costs ~16s of wall clock. That duration is intrinsic to the bug — it is the gap the fix
 * has to survive — not a sleep for convenience.
 */
class SseNettyWriteTimeoutSpec :
    StringSpec({

        "a real Netty stream survives a first event that arrives long after the engine's write timeout"
            .config(timeout = 90.seconds) {
                // The production default, not a test constant: if someone raises heartbeat-s
                // back above the engine cap, this test is what fails.
                val heartbeatMs = ConfigFactory.load().getLong("iris.stream.heartbeat-s") * 1000

                // Well past Ktor's 10s Netty default — this is the cliff being tested.
                val firstEventDelayMs = 15_000L

                val server =
                    KtorServerBootstrap.createServer(
                        KtorServerConfig(
                            serviceName = "st-p1-netty-test",
                            serverPort = 0, // ephemeral
                            engine = KtorEngine.NETTY,
                        ),
                    ) {
                        routing {
                            post("/stream") {
                                call.respondSse(heartbeatMs) { emit ->
                                    delay(firstEventDelayMs)
                                    emit("event: late\ndata: {\"ok\":true}\n\n")
                                }
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

                    // The client must never be the thing that gives up — otherwise a client
                    // timeout would masquerade as the server defect. Mirrors GolemV1HttpClient.
                    HttpClient(CIO) {
                        install(HttpTimeout) {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.use { client ->
                        client.preparePost("http://127.0.0.1:$port/stream").execute { response ->
                            response.status shouldBe HttpStatusCode.OK
                            val channel = response.bodyAsChannel()

                            // Committed immediately — long before the 10s engine cap.
                            withTimeout(5_000) { channel.readUTF8Line() } shouldBe ": ready"

                            // And the stream is still alive 15s later to carry the real event.
                            val late =
                                withTimeout(firstEventDelayMs + 20_000) {
                                    buildString {
                                        while (true) {
                                            val line = channel.readUTF8Line() ?: break
                                            appendLine(line)
                                            if (line.startsWith("data:")) break
                                        }
                                    }
                                }
                            late shouldContain "event: late"
                            late shouldContain "\"ok\":true"
                        }
                    }
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
                }
            }
    })
