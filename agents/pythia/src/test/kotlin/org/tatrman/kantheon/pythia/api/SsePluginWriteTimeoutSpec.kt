package org.tatrman.kantheon.pythia.api

import io.kotest.assertions.withClue
import io.kotest.core.Tag
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
import kotlinx.coroutines.TimeoutCancellationException
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
 * **Both cases are tagged [Slow]** — between them they spend ~25s waiting on real sockets,
 * and that duration is intrinsic to the defect (it is the gap the fix has to survive), not
 * a sleep for convenience. Skip them in a fast local loop with:
 *
 * ```
 * KOTEST_TAGS='!Slow' ./gradlew :agents:pythia:test
 * ```
 *
 * (Kotest reads the tag expression from the `KOTEST_TAGS` **environment** variable; the
 * `kotest.tags` system property would have to be forwarded to the forked test JVM by the
 * build, and it is not.) CI runs them — they are the regression net for the outage.
 *
 * See `project/server/features/stream-timeouts/`.
 */
class SsePluginWriteTimeoutSpec :
    StringSpec({

        "WITHOUT a preamble, a quiet sse{} handler is reaped by the engine — the hazard"
            .config(timeout = 90.seconds, tags = setOf(Slow)) {
                // Past Ktor's 10s Netty `responseWriteTimeoutSeconds` default — the cliff.
                val firstFrameDelayMs = 15_000L

                val server =
                    KtorServerBootstrap.createServer(
                        KtorServerConfig(
                            serviceName = "st-p1-pythia-sse-test",
                            serverPort = 0,
                            engine = KtorEngine.NETTY,
                            // PINNED, not inherited (ST-P2·S2, closing review-077 R1). This case
                            // exists to prove the engine reaps a quiet handler, so it must own the
                            // timeout it is proving. It used to inherit Ktor's 10s default via
                            // ktor-configurator 0.9.4; since 0.10.1 the library default is 180s,
                            // and inheriting that would have made this case hang for 15s and then
                            // PASS the request — going red while reporting nothing about the
                            // defect. The 15s delay below is what must exceed this value.
                            responseWriteTimeoutSeconds = 10,
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
                        //
                        // "It threw" is NOT enough to conclude that (review-077 R2): a bind
                        // failure, a refused connection, or our own `withTimeout` expiring would
                        // all throw too, and then the test would pass while measuring nothing.
                        // So assert the SHAPE of the failure, not merely its presence.
                        val startedAt = System.nanoTime()
                        val outcome =
                            runCatching {
                                client.prepareGet("http://127.0.0.1:$port/slow").execute { response ->
                                    withTimeout(firstFrameDelayMs + 20_000) {
                                        response.bodyAsChannel().readUTF8Line()
                                    }
                                }
                            }
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                        outcome.isFailure shouldBe true

                        withClue(
                            "the read failed with a TimeoutCancellationException, which means the socket " +
                                "stayed OPEN until our own ${firstFrameDelayMs + 20_000}ms deadline — the " +
                                "opposite of the property under test. The engine reaping the response is " +
                                "supposed to close it. Failure was: ${outcome.exceptionOrNull()}",
                        ) {
                            (outcome.exceptionOrNull() is TimeoutCancellationException) shouldBe false
                        }

                        withClue(
                            "the failure arrived after ${elapsedMs}ms, at or past the handler's own " +
                                "${firstFrameDelayMs}ms delay — an engine reap must land on the ~10s " +
                                "write-timeout cap, i.e. BEFORE the handler would have sent anything. " +
                                "A later failure is some other fault wearing this test's clothes.",
                        ) {
                            (elapsedMs < firstFrameDelayMs) shouldBe true
                        }
                    }
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
                }
            }

        "WITH a comment preamble, the same quiet handler survives — the fix"
            .config(timeout = 90.seconds, tags = setOf(Slow)) {
                val firstFrameDelayMs = 15_000L

                val server =
                    KtorServerBootstrap.createServer(
                        KtorServerConfig(
                            serviceName = "st-p1-pythia-sse-fixed",
                            serverPort = 0,
                            engine = KtorEngine.NETTY,
                            // Pinned for the same reason as the hazard case above, and it matters
                            // MORE here. review-077 R1 said to leave this case inheriting, since it
                            // "stays green either way" — but that is precisely the failure mode: at
                            // the 180s library default the 15s delay never reaches any cap, so this
                            // case would pass even with the preamble DELETED. It would look like a
                            // regression net while catching nothing. At 10s the preamble is once
                            // again the only reason the stream survives.
                            responseWriteTimeoutSeconds = 10,
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

/**
 * Marks a case whose wall-clock cost is intrinsic to what it measures — here, real sockets
 * held open across the engine's 10s write-timeout cap. Excluded with
 * `KOTEST_TAGS='!Slow'`. Deliberately declared per-module: `agents/` has no shared test
 * source set, and one duplicated `Tag("Slow")` beats inventing one.
 */
private val Slow = Tag("Slow")
