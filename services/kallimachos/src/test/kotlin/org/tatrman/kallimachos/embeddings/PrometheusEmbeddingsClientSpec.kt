package org.tatrman.kallimachos.embeddings

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post as wmPost
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * P2 Stage 2.1 T2 — the Prometheus embeddings client against a Wiremock'd
 * Prometheus (contracts §10; EXAMPLES §9). Stubs the REAL OpenAI-shaped surface
 * Prometheus serves: `POST /api/v1/embeddings` → `{data:[{index, embedding}]}`.
 * The conformed dimension N is checked against each embedding's length (mismatch
 * = config error); a data count that disagrees with the inputs throws; an embed
 * error throws (the service catches → PENDING).
 */
class PrometheusEmbeddingsClientSpec :
    StringSpec({
        val config = EmbedConfig(modelId = "bge-m3", modelVersion = "1", dimensions = 3)

        fun client(
            wm: WireMockServer,
            cfg: EmbedConfig = config,
        ): PrometheusEmbeddingsClient {
            val http = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            return PrometheusEmbeddingsClient(http, "http://localhost:${wm.port()}", cfg)
        }

        "embeds a batch and returns one vector per input (ordered by index)" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
            try {
                // Deliberately out of order, with extra OpenAI keys — the client must
                // restore submission order by `index` and ignore the unknown keys.
                val body =
                    """
                    {"object":"list","model":"bge-m3","data":[
                      {"object":"embedding","index":1,"embedding":[0.4,0.5,0.6]},
                      {"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}
                    ]}
                    """.trimIndent()
                wm.stubFor(
                    wmPost(urlPathEqualTo("/api/v1/embeddings")).willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body),
                    ),
                )
                val result = runBlocking { client(wm).embed(listOf("alpha", "beta")) }
                result.vectors.size shouldBe 2
                result.vectors[0].toList() shouldBe listOf(0.1f, 0.2f, 0.3f)
                result.vectors[1].toList() shouldBe listOf(0.4f, 0.5f, 0.6f)
                result.dimensions shouldBe 3
            } finally {
                wm.stop()
            }
        }

        "a dimension mismatch is a config error" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/api/v1/embeddings")).willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"data":[{"index":0,"embedding":[0.1,0.2]}],"model":"bge-m3"}"""),
                    ),
                )
                shouldThrow<EmbeddingDimensionMismatch> { runBlocking { client(wm).embed(listOf("alpha")) } }
            } finally {
                wm.stop()
            }
        }

        "a count mismatch (fewer vectors than inputs) throws rather than mis-aligning" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
            try {
                wm.stubFor(
                    wmPost(urlPathEqualTo("/api/v1/embeddings")).willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}],"model":"bge-m3"}"""),
                    ),
                )
                shouldThrow<EmbeddingCountMismatch> { runBlocking { client(wm).embed(listOf("alpha", "beta")) } }
            } finally {
                wm.stop()
            }
        }

        "an embed HTTP error throws (the service turns this into PENDING)" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
            try {
                wm.stubFor(wmPost(urlPathEqualTo("/api/v1/embeddings")).willReturn(aResponse().withStatus(503)))
                shouldThrow<Exception> { runBlocking { client(wm).embed(listOf("alpha")) } }
            } finally {
                wm.stop()
            }
        }

        "an empty batch short-circuits with no call" {
            val wm = WireMockServer(WireMockConfiguration.options().dynamicPort()).also { it.start() }
            try {
                runBlocking { client(wm).embed(emptyList()) }.vectors.size shouldBe 0
            } finally {
                wm.stop()
            }
        }
    })
