package org.tatrman.kantheon.sysifos.bff.stream

import io.kotest.assertions.withClue
import io.kotest.core.Tag
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeout
import org.tatrman.kantheon.sysifos.bff.bearer
import org.tatrman.kantheon.sysifos.bff.module
import org.tatrman.kantheon.sysifos.bff.testDeps
import kotlin.time.Duration.Companion.seconds

/**
 * review-078 R1 — the regression test for sysifos-bff's copy of the 2026-07-29 Hartland defect.
 *
 * Unlike [StreamRouteSpec], this stands up a **real Netty socket**, because the defect lives in
 * the engine and `testApplication`'s in-memory engine cannot reproduce it.
 *
 * This service does **not** go through the shared `KtorServerBootstrap` — `Application.kt` calls
 * `embeddedServer(Netty, port = httpPort)` directly — so ST-P2's 180s
 * `responseWriteTimeoutSeconds` never reaches it and it inherits Ktor's 10s default, which caps
 * *time-to-first-byte* for a streaming response. `/stream` opens `respondTextWriter` (arming that
 * timeout) and, on a quiet session, wrote nothing until the heartbeat — which defaulted to **30s**.
 * The socket was reaped at 10s with no status line and the caller saw a 502.
 *
 * The timeout is **pinned at 10 rather than inherited**, deliberately. The production wiring has no
 * `configure` block at all, so 10 is what it actually gets today; and a test that inherits a value
 * stops measuring anything the moment that value moves. This mirrors what ST-P2·S2 did to the other
 * three ST specs.
 *
 * The heartbeat is set **above** the engine cap on purpose: it makes the preamble the only thing
 * that can produce an early byte, so this spec measures the preamble and nothing else. The shipped
 * default is 5s (`application.conf`) — that is defence-in-depth against proxy idle-read timeouts,
 * a separate family of cut, and is asserted by [StreamRouteSpec].
 *
 * Costs ~25s of wall clock. That duration is intrinsic to what it measures — it is the gap the fix
 * has to survive — not a sleep for convenience. Tagged [Slow] so a fast local loop can skip it:
 *
 * ```
 * KOTEST_TAGS='!Slow' ./gradlew :agents:sysifos-bff:test
 * ```
 *
 * (Kotest reads the tag expression from the `KOTEST_TAGS` **environment** variable; the
 * `kotest.tags` system property would have to be forwarded to the forked test JVM by the build,
 * and it is not.) CI runs it — it is the regression net.
 *
 * See `project/server/features/stream-timeouts/`.
 */
class SseNettyWriteTimeoutSpec :
    StringSpec({

        "a real Netty /stream survives long past the engine's write timeout on a quiet session"
            .config(timeout = 90.seconds, tags = setOf(Slow)) {
                // Well past the 10s cap below: the first heartbeat cannot arrive before the engine
                // would have reaped an uncommitted response, so only the preamble can save it.
                val heartbeatMs = 20_000L

                val server =
                    embeddedServer(
                        Netty,
                        configure = {
                            connector {
                                port = 0 // ephemeral
                                host = "127.0.0.1"
                            }
                            // PINNED, not inherited — see the KDoc. This is the value production
                            // gets today, and the value this spec exists to prove survivable.
                            responseWriteTimeoutSeconds = 10
                        },
                    ) { module(testDeps(heartbeatMs = heartbeatMs)) }

                server.start(wait = false)
                try {
                    val port =
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port

                    // The client must never be the thing that gives up — otherwise a client
                    // timeout would masquerade as the server defect.
                    HttpClient(CIO) {
                        install(HttpTimeout) {
                            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        }
                    }.use { client ->
                        client
                            .prepareGet("http://127.0.0.1:$port/stream") {
                                header(HttpHeaders.Authorization, bearer("""{"sub":"u1","tenant":"acme"}"""))
                            }.execute { response ->
                                response.status shouldBe HttpStatusCode.OK
                                val channel = response.bodyAsChannel()

                                // Committed immediately — long before the 10s engine cap.
                                withClue("the `: ready` preamble must be the first line on the wire") {
                                    withTimeout(5_000) { channel.readUTF8Line() } shouldBe ": ready"
                                }

                                // The blank line is the SSE frame boundary: a preamble written as
                                // `": ready\n"` would pass the assertion above while silently
                                // merging into the following frame.
                                withClue("the preamble is a whole SSE frame, terminated by a blank line") {
                                    withTimeout(5_000) { channel.readUTF8Line() } shouldBe ""
                                }

                                // And the stream is still alive 20s later — twice the engine cap —
                                // to carry the first heartbeat. This is the half that would fail if
                                // the preamble were removed: nothing would have committed the
                                // response, and the socket would be gone at 10s.
                                val frames =
                                    withTimeout(heartbeatMs + 20_000) {
                                        buildString {
                                            while (true) {
                                                val line = channel.readUTF8Line() ?: break
                                                appendLine(line)
                                                if (line.startsWith("data:")) break
                                            }
                                        }
                                    }

                                withClue(
                                    "the stream had to stay open past the ${heartbeatMs}ms heartbeat, " +
                                        "which is twice the engine's pinned 10s write timeout. Got: $frames",
                                ) {
                                    frames shouldContain "heartbeat"
                                }
                            }
                    }
                } finally {
                    server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
                }
            }
    })

/**
 * Marks a case whose wall-clock cost is intrinsic to what it measures — here, a real socket held
 * open across the engine's 10s write-timeout cap. Excluded with `KOTEST_TAGS='!Slow'`.
 * Deliberately declared per-module: `agents/` has no shared test source set, and one duplicated
 * `Tag("Slow")` beats inventing one.
 */
private val Slow = Tag("Slow")
