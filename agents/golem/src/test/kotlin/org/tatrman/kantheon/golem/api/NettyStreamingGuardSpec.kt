package org.tatrman.kantheon.golem.api

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * ST-P1·S2·T3 — the estate-wide tripwire for the streaming write-timeout class of bug.
 *
 * Ktor's Netty engine caps **time-to-first-byte** at `responseWriteTimeoutSeconds`
 * (default 10s) and `KtorServerBootstrap` does not override it, so any Netty service that
 * serves a stream must commit its response early — a preamble frame — and keep its
 * keepalive under that cap. Getting this wrong is silent on the server and surfaces as a
 * 502 to the user; that is what took the Hartland demo down on 2026-07-29.
 *
 * Three services use `KtorEngine.NETTY` today and all three have been reviewed:
 *
 *  - **golem** — `SseAnswer` writes `": ready"` before the turn starts, pings every
 *    [PING_INTERVAL_MS]. This was already correct and is the estate's reference shape.
 *  - **iris-bff** — fixed in ST-P1·S1 (`SseStream` preamble + 5s heartbeat), pinned by
 *    `SsePreambleSpec` and `SseNettyWriteTimeoutSpec`.
 *  - **pythia** — fixed in ST-P1·S2 (`SseRoutes` preamble); Ktor's `sse { }` plugin was
 *    measured NOT to commit on its own, pinned by `SsePluginWriteTimeoutSpec`.
 *
 * A **fourth** Netty service would inherit the same cliff with nothing to catch it, so the
 * allow-list below is deliberately exact: adding one fails this test and sends the author
 * to the effort docs. This lives in golem because golem is the reference implementation.
 *
 * See `project/server/features/stream-timeouts/`.
 */
class NettyStreamingGuardSpec :
    StringSpec({

        "golem's SSE keepalive stays below Ktor's 10s Netty write-timeout default" {
            // Asserted against the real constant, not a copy — a copy would drift.
            withClue("PING_INTERVAL_MS=$PING_INTERVAL_MS must stay under the 10s engine cap") {
                (PING_INTERVAL_MS < KTOR_NETTY_WRITE_TIMEOUT_FLOOR_MS) shouldBe true
            }
        }

        "the set of KtorEngine.NETTY services is exactly the reviewed allow-list" {
            val repoRoot =
                generateSequence(File(".").absoluteFile) { it.parentFile }
                    .first { File(it, "settings.gradle.kts").exists() }

            val netty =
                File(repoRoot, "agents")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    // Production wiring only: the ST specs themselves stand up Netty servers
                    // on purpose, and build output is not source.
                    .filter { "${File.separator}src${File.separator}main${File.separator}" in it.path }
                    .filter { "${File.separator}build${File.separator}" !in it.path }
                    .filter { "KtorEngine.NETTY" in it.readText() }
                    .map { it.relativeTo(repoRoot).path.replace(File.separatorChar, '/') }
                    .toSortedSet()

            withClue(
                "A service was added to or removed from the Netty set. Every Netty service must " +
                    "commit its SSE response early (a preamble frame) and keep any keepalive under " +
                    "Ktor's 10s responseWriteTimeoutSeconds default, or long responses are reaped " +
                    "before their first byte and surface as 502s. Read " +
                    "project/server/features/stream-timeouts/ before updating this list. Found: $netty",
            ) {
                netty shouldBe
                    sortedSetOf(
                        "agents/golem/src/main/kotlin/org/tatrman/kantheon/golem/Application.kt",
                        "agents/iris-bff/src/main/kotlin/org/tatrman/kantheon/iris/Application.kt",
                        "agents/pythia/src/main/kotlin/org/tatrman/kantheon/pythia/Application.kt",
                    )
            }
        }
    })

/** Ktor's `NettyApplicationEngine.Configuration.responseWriteTimeoutSeconds` default, in ms. */
private const val KTOR_NETTY_WRITE_TIMEOUT_FLOOR_MS = 10_000L
