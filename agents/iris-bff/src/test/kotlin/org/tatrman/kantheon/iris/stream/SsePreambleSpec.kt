package org.tatrman.kantheon.iris.stream

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString

/**
 * ST-P1·S1 — the SSE response must be committed before any slow work runs.
 *
 * Ktor's Netty engine reaps a response that has produced no bytes within
 * `responseWriteTimeoutSeconds` (default **10s**). A Themis cold resolve is ~19-28s, so a
 * stream that stays silent until its first heartbeat (which defaulted to 15s) had its
 * socket closed before a single header went out — surfacing to the user as a 502 with
 * `time_starttransfer=0`. See `project/server/features/stream-timeouts/`.
 *
 * This spec pins the **ordering** contract, which is engine-independent: `testApplication`
 * runs Ktor's in-memory test engine, not Netty. The engine behaviour itself is pinned by
 * [SseNettyWriteTimeoutSpec], which stands up a real socket. Both are needed; neither
 * subsumes the other.
 */
class SsePreambleSpec :
    StringSpec({

        "writes the `: ready` preamble before the body produces anything" {
            // The gate keeps `body` suspended for the whole assertion, so a preamble that
            // arrives can only have come from respondSse itself — not from the dispatch.
            val gate = CompletableDeferred<Unit>()
            testApplication {
                application {
                    routing {
                        post("/stream") {
                            // 60s heartbeat: far longer than this test waits, so a passing
                            // assertion cannot be explained by the heartbeat ticker.
                            call.respondSse(heartbeatMs = 60_000) { emit ->
                                gate.await()
                                emit("event: late\ndata: {}\n\n")
                            }
                        }
                    }
                }

                client.preparePost("/stream").execute { response ->
                    response.status shouldBe HttpStatusCode.OK
                    val channel = response.bodyAsChannel()

                    val firstLine = withTimeout(5_000) { channel.readUTF8Line() }
                    firstLine shouldBe ": ready"

                    // The blank line is the SSE frame boundary. A preamble written as
                    // `": ready\n"` would pass the assertion above while silently merging
                    // into the following event block, so the terminator is pinned too.
                    withClue("the `: ready` preamble must be terminated by a blank line — it is a whole SSE frame") {
                        withTimeout(5_000) { channel.readUTF8Line() } shouldBe ""
                    }

                    // The body is still parked — the preamble genuinely preceded it.
                    gate.isCompleted shouldBe false

                    gate.complete(Unit)
                    val rest = withTimeout(5_000) { channel.readRemaining().readString() }
                    rest shouldContain "event: late"
                }
            }
        }

        // ST-P1·S1·T5 — defence-in-depth, machine-checked.
        //
        // Note what this does and does not claim. MEASURED 2026-07-29: the engine's write
        // timeout caps time-to-first-byte only — with the preamble in place, a stream
        // survived a 15s idle gap on a real Netty socket even at heartbeat-s = 60. So the
        // preamble is the fix, and this guard is the second line: if the preamble is ever
        // removed or bypassed, a sub-10s heartbeat still commits the response in time.
        //
        // 10s is *Ktor's Netty default*, i.e. the worst case iris-bff must survive if it is
        // ever deployed against a library build predating ST-P2. Do NOT raise this floor to
        // 180 when ST-P2 lands: the floor exists precisely to keep the service safe without
        // the library fix.
        "the configured SSE heartbeat stays below Ktor's 10s Netty write-timeout default" {
            val heartbeatS = ConfigFactory.load().getLong("iris.stream.heartbeat-s")
            withClue(
                "iris.stream.heartbeat-s=$heartbeatS is at or above the ${KTOR_NETTY_WRITE_TIMEOUT_FLOOR_S}s " +
                    "Ktor Netty responseWriteTimeoutSeconds default — the stream would be reaped before the " +
                    "first heartbeat could commit it. See project/server/features/stream-timeouts/.",
            ) {
                (heartbeatS < KTOR_NETTY_WRITE_TIMEOUT_FLOOR_S) shouldBe true
            }
        }
    })

/** Ktor's `NettyApplicationEngine.Configuration.responseWriteTimeoutSeconds` default. */
private const val KTOR_NETTY_WRITE_TIMEOUT_FLOOR_S = 10L
