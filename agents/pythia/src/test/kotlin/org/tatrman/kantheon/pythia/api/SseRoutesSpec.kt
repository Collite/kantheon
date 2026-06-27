package org.tatrman.kantheon.pythia.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication

/**
 * Stage 1.3 T4 — the SSE event bridge replays `pythia_events` from `from_seq`
 * (PG-replay; the NATS live-tail is integration-deferred). The replayed sequence
 * matches the emitted trace and `from_seq=N` skips the first N frames. Consumed
 * via the ktor SSE client plugin (the robust way to drain a stream in tests).
 */
class SseRoutesSpec :
    StringSpec({

        suspend fun io.ktor.client.HttpClient.frames(
            id: String,
            fromSeq: Int,
            bearer: String?,
        ): List<String> {
            val collected = mutableListOf<String>()
            sse(
                urlString = "/v1/investigations/$id/events?from_seq=$fromSeq",
                request = { bearer?.let { bearerAuth(it) } },
            ) {
                incoming.collect { ev -> ev.data?.let { collected.add(it) } }
            }
            return collected
        }

        "from_seq=0 replays the full trace; from_seq=N skips the first N; frames are valid events" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val sseClient = createClient { install(SSE) }
                val submit =
                    client.post("/v1/investigations") {
                        bearerAuth("u1")
                        setBody("""{"question":"q","caller":{"kind":"IRIS"}}""")
                    }
                val id = Regex("\"id\":\"([^\"]+)\"").find(submit.bodyAsText())!!.groupValues[1]

                val full = sseClient.frames(id, 0, "u1")
                full.size shouldBeGreaterThan 5
                full.all { it.contains("investigationId") } shouldBe true

                val skipped = sseClient.frames(id, 5, "u1")
                skipped.size shouldBe (full.size - 5)
            }
        }

        "SSE without a bearer emits a single terminal error frame (denied, not silently idle)" {
            testApplication {
                val h = PythiaTestHarness().also { it.mount(this) }
                val sseClient = createClient { install(SSE) }
                val id = h.seed(org.tatrman.kantheon.pythia.v1.Status.STATUS_DONE)
                // A denied stream is distinguishable from "no events yet": exactly one
                // `error` frame rather than a silent empty 200 (H6).
                val frames = sseClient.frames(id.toString(), 0, bearer = null)
                frames.size shouldBe 1
                frames.single().contains("forbidden") shouldBe true
            }
        }
    })
