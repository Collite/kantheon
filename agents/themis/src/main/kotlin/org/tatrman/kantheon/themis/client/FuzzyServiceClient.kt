package org.tatrman.kantheon.themis.client

import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.koog.FuzzyCandidate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class FuzzyServiceClient(
    private val config: EndpointConfig,
) {
    private val logger = LoggerFactory.getLogger(FuzzyServiceClient::class.java)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val httpClient =
        HttpClient(ClientCIO) {
            install(ContentNegotiation) {
                json(this@FuzzyServiceClient.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = config.timeoutMs
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = config.timeoutMs
            }
        }

    suspend fun match(
        category: String,
        name: String,
        algorithm: String = "TATRMAN",
        limit: Int = 10,
    ): List<FuzzyCandidate> =
        try {
            val request =
                MatchRequestDto(
                    query = name,
                    category = category.ifBlank { null },
                    algorithm = algorithm,
                    limit = limit,
                )

            val response: MatchResponseDto =
                httpClient
                    .post("http://${config.host}:${config.port}/match") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }.body()

            response.matches.map { match ->
                FuzzyCandidate(
                    fuzzyId = match.id,
                    fuzzyLabel = match.label,
                    score = match.score,
                    entityTypeRef = category,
                )
            }
        } catch (e: Exception) {
            logger.error("Error calling fuzzy service: name='{}', category='{}'", name, category, e)
            emptyList()
        }

    fun close() {
        httpClient.close()
    }
}

@Serializable
private data class MatchRequestDto(
    val query: String,
    val category: String? = null,
    val algorithm: String = "TATRMAN",
    val limit: Int = 10,
)

@Serializable
private data class MatchResponseDto(
    val matches: List<MatchDto> = emptyList(),
    val isError: Boolean = false,
    val error: String? = null,
)

@Serializable
private data class MatchDto(
    val id: String,
    val label: String,
    val score: Double,
)
