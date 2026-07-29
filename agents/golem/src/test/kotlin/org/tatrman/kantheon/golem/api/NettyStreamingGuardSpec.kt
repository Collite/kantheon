package org.tatrman.kantheon.golem.api

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * ST-P1·S2·T3 — the estate-wide tripwire for the streaming write-timeout class of bug.
 *
 * Ktor's Netty engine caps **time-to-first-byte** at `responseWriteTimeoutSeconds`, so any
 * Netty service that serves a **stream** must commit its response early — a preamble frame —
 * rather than letting the socket sit silent while it computes. Getting this wrong is silent
 * on the server and surfaces as a 502 to the user; that is what took the Hartland demo down
 * on 2026-07-29.
 *
 * ## Two populations, and they are not the same set
 *
 * The scan covers the **whole repo** (excluding `frontends/`, `node_modules/` and build
 * output) and catches both ways a Netty server gets started here:
 *
 *  1. **Shared-bootstrap consumers** — `KtorServerBootstrap.createServer` with
 *     `KtorEngine.NETTY`. Since ktor-configurator 0.10.1 (ST-P2) these get an explicit
 *     **180s** timeout from the library, so the engine cap is no longer a cliff for them.
 *  2. **Raw `embeddedServer(Netty, ...)`** — these bypass the shared bootstrap entirely and
 *     therefore inherit Ktor's **10s** default. ST-P2 cannot help a service that does not
 *     call the library.
 *
 * Scanning only for `KtorEngine.NETTY` — as this guard did until review-078 R2 — measured
 * population 1 while claiming to cover both, and missed nine raw-Netty servers plus
 * sysifos-bff, which had the Hartland defect verbatim and shipped it to two clusters.
 *
 * The cap only bites a **streaming** response (`respondTextWriter` / `sse {}` /
 * `respondOutputStream`): it arms on a *pending write*, so a plain handler that thinks for
 * 30s and then responds is never reaped (`contracts.md` §1). That is why the raw-Netty list
 * below records, per entry, whether the service streams — it is the property that decides
 * whether an entry is a hazard or just an inventory item.
 *
 * Reviewed streaming services, all three correct:
 *
 *  - **golem** — `SseAnswer` writes `": ready"` before the turn starts, pings every
 *    [PING_INTERVAL_MS]. This was already correct and is the estate's reference shape.
 *  - **iris-bff** — fixed in ST-P1·S1 (`SseStream` preamble + 5s heartbeat), pinned by
 *    `SsePreambleSpec` and `SseNettyWriteTimeoutSpec`.
 *  - **pythia** — fixed in ST-P1·S2 (`SseRoutes` preamble); Ktor's `sse { }` plugin was
 *    measured NOT to commit on its own, pinned by `SsePluginWriteTimeoutSpec`.
 *  - **sysifos-bff** — raw Netty, fixed in review-078 R1 (preamble + 5s heartbeat), pinned
 *    by its own `SseNettyWriteTimeoutSpec`.
 *
 * Both allow-lists are deliberately exact: adding a Netty server of either kind fails this
 * test and sends the author to the effort docs. This lives in golem because golem is the
 * reference implementation.
 *
 * See `project/server/features/stream-timeouts/`.
 */
class NettyStreamingGuardSpec :
    StringSpec({

        "golem's SSE keepalive stays below the 10s Netty write-timeout floor" {
            // Asserted against the real constant, not a copy — a copy would drift.
            withClue(
                "PING_INTERVAL_MS=$PING_INTERVAL_MS must stay under the 10s floor. Golem gets " +
                    "ST-P2's 180s from KtorServerBootstrap, so 10s is not its live cap — the floor " +
                    "deliberately encodes 'survive even against a library build that predates " +
                    "ST-P2, or a raw-Netty deployment'. Do not raise it to 180.",
            ) {
                (PING_INTERVAL_MS < KTOR_NETTY_WRITE_TIMEOUT_FLOOR_MS) shouldBe true
            }
        }

        "the set of Netty servers — bootstrap and raw — is exactly the reviewed allow-list" {
            val repoRoot =
                generateSequence(File(".").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "settings.gradle.kts").exists() }
                    ?: error(
                        "NettyStreamingGuardSpec scans repo source and could not find a parent " +
                            "directory containing settings.gradle.kts, walking up from " +
                            "${File(".").absolutePath}. This test must run with its working " +
                            "directory inside the checkout.",
                    )

            val sources =
                repoRoot
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    // Production wiring only: the ST specs themselves stand up Netty servers
                    // on purpose, and build output is not source.
                    .filter { "${File.separator}src${File.separator}main${File.separator}" in it.path }
                    .filter { "${File.separator}build${File.separator}" !in it.path }
                    // Not source of ours, and walking them is slow.
                    .filter { "${File.separator}node_modules${File.separator}" !in it.path }
                    .filter { "${File.separator}frontends${File.separator}" !in it.path }
                    // One read per file — both markers are matched against the same text.
                    .map { it.relativeTo(repoRoot).path.replace(File.separatorChar, '/') to it.readText() }
                    .toList()

            fun matching(marker: (String) -> Boolean) =
                sources
                    .filter { (_, text) -> marker(text) }
                    .map { (path, _) -> path }
                    .toSortedSet()

            // Population 1 — through the shared bootstrap, so ST-P2's explicit 180s applies.
            val viaBootstrap = matching { "KtorEngine.NETTY" in it }

            // Population 2 — raw engine, so Ktor's 10s default applies and ST-P2 cannot reach it.
            val rawNetty = matching { "io.ktor.server.netty.Netty" in it }

            withClue(
                "A service was added to or removed from the set that starts Netty through " +
                    "KtorServerBootstrap. These DO get ST-P2's explicit 180s write timeout, so the " +
                    "engine cap is not a cliff for them — but a streaming endpoint still wants a " +
                    "preamble so it never depends on that value. Read " +
                    "project/server/features/stream-timeouts/ before updating this list. " +
                    "Found: $viaBootstrap",
            ) {
                viaBootstrap shouldBe
                    sortedSetOf(
                        "agents/golem/src/main/kotlin/org/tatrman/kantheon/golem/Application.kt",
                        "agents/iris-bff/src/main/kotlin/org/tatrman/kantheon/iris/Application.kt",
                        "agents/pythia/src/main/kotlin/org/tatrman/kantheon/pythia/Application.kt",
                    )
            }

            withClue(
                "A service that starts Netty DIRECTLY was added or removed. This is the dangerous " +
                    "population: bypassing KtorServerBootstrap means ST-P2's 180s never applies and " +
                    "the service inherits Ktor's 10s cap on TIME-TO-FIRST-BYTE. That only bites a " +
                    "STREAMING response (respondTextWriter / sse {} / respondOutputStream) — the " +
                    "timeout arms on a pending write, so a plain slow handler is never reaped. " +
                    "So: adding a NON-streaming raw-Netty service is fine, just add it below with a " +
                    "`// no streaming` note. Adding a STREAMING one means writing a `\": ready\"` " +
                    "preamble first (golem's SseAnswer is the reference shape) and pinning it with a " +
                    "real-socket spec. Read project/server/features/stream-timeouts/ before updating " +
                    "this list. Found: $rawNetty",
            ) {
                rawNetty shouldBe
                    sortedSetOf(
                        // no streaming
                        "agents/hebe/modules/gateway/src/main/kotlin/org/tatrman/kantheon/hebe/gateway/Gateway.kt",
                        // no streaming
                        "agents/kleio/src/main/kotlin/org/tatrman/kantheon/kleio/Application.kt",
                        // no streaming
                        "agents/midas/core/src/main/kotlin/org/tatrman/kantheon/midas/core/Application.kt",
                        // no streaming
                        "agents/midas/loaders/excel/src/main/kotlin/org/tatrman/kantheon/midas/loaders/excel/" +
                            "Application.kt",
                        // no streaming
                        "agents/midas/loaders/google-finance/src/main/kotlin/org/tatrman/kantheon/midas/loaders/" +
                            "googlefinance/Application.kt",
                        // STREAMS — /stream; preamble added in review-078 R1, pinned by its own
                        // SseNettyWriteTimeoutSpec. This one shipped the Hartland defect to two clusters.
                        "agents/sysifos-bff/src/main/kotlin/org/tatrman/kantheon/sysifos/bff/Application.kt",
                        // no streaming (two servers in this one file: app + probe)
                        "services/kallimachos/src/main/kotlin/org/tatrman/kallimachos/Application.kt",
                        // no streaming
                        "services/pinakes/src/main/kotlin/org/tatrman/pinakes/Application.kt",
                        // no streaming
                        "services/report-renderer/src/main/kotlin/org/tatrman/kantheon/report/Application.kt",
                    )
            }
        }
    })

/**
 * The floor any estate keepalive must stay under, in ms — Ktor's *unconfigured*
 * `NettyApplicationEngine.Configuration.responseWriteTimeoutSeconds`. It is no longer what
 * bootstrap consumers run at (ST-P2 sets 180s), and that is the point: the floor encodes
 * "survive even against a library build that predates ST-P2, or a raw `embeddedServer(Netty)`
 * that never sees the library at all". Deliberately not raised — see `tasks-st-p2-s2.md` T3.
 */
private const val KTOR_NETTY_WRITE_TIMEOUT_FLOOR_MS = 10_000L
