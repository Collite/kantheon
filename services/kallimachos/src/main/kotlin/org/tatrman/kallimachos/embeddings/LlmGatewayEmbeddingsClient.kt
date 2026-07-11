package org.tatrman.kallimachos.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Embeddings via the LLM gateway (contracts §10). The LLM gateway exposes the
 * OpenAI-shaped embeddings surface — `POST /api/v1/embeddings`, request
 * `{model, input:{texts:[...]}}`, response `{data:[{index, embedding}], model}` —
 * so this client speaks that wire directly. Batched; one `data` item per input,
 * keyed by `index`, which we sort by to restore submission order.
 *
 * There is no top-level `dimensions` field: the conformed dimension is checked
 * against the actual embedding length. A mismatch is a config error (two
 * embedding spaces would fragment the corpus, architecture §11), surfaced as
 * [EmbeddingDimensionMismatch]. A `data` length that does not match the inputs is
 * surfaced as [EmbeddingCountMismatch] rather than silently mis-aligning vectors.
 */
class EmbeddingDimensionMismatch(
    val expected: Int,
    val actual: Int,
) : RuntimeException("embedding dimension $actual != conformed $expected")

class EmbeddingCountMismatch(
    val expected: Int,
    val actual: Int,
) : RuntimeException("embedding count $actual != requested $expected")

class LlmGatewayEmbeddingsClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val config: EmbedConfig,
) : EmbeddingsPort {
    @Serializable
    private data class EmbedInput(
        val texts: List<String>,
    )

    @Serializable
    private data class EmbedBody(
        val model: String,
        val input: EmbedInput,
    )

    @Serializable
    private data class EmbedItem(
        val index: Int = 0,
        val embedding: List<Float> = emptyList(),
    )

    @Serializable
    private data class EmbedReply(
        val data: List<EmbedItem> = emptyList(),
        val model: String = "",
        @SerialName("object") val obj: String = "list",
    )

    override suspend fun embed(texts: List<String>): EmbedResult {
        if (texts.isEmpty()) return EmbedResult(emptyList(), config.modelId, config.modelVersion, config.dimensions)
        val resp: HttpResponse =
            http.post("$baseUrl/api/v1/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(EmbedBody(config.modelId, EmbedInput(texts)))
            }
        require(resp.status.isSuccess()) { "LLM-gateway embeddings failed: ${resp.status}" }
        val reply: EmbedReply = resp.body()
        if (reply.data.size != texts.size) throw EmbeddingCountMismatch(texts.size, reply.data.size)
        // Restore submission order — OpenAI-shaped responses carry an `index` per item.
        val vectors = reply.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
        vectors.forEach { v ->
            if (v.size != config.dimensions) throw EmbeddingDimensionMismatch(config.dimensions, v.size)
        }
        return EmbedResult(
            vectors = vectors,
            modelId = config.modelId,
            modelVersion = config.modelVersion,
            dimensions = config.dimensions,
        )
    }
}
