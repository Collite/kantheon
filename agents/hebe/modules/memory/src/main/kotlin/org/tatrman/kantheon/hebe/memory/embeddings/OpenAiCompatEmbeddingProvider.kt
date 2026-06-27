package org.tatrman.kantheon.hebe.memory.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCompatEmbeddingProvider(
    private val client: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    override val model: String,
    override val dim: Int,
    private val batchSize: Int = 32,
) : EmbeddingProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val results = mutableListOf<FloatArray>()
        var offset = 0

        while (offset < texts.size) {
            val batch = texts.subList(offset, (offset + batchSize).coerceAtMost(texts.size))
            val embeddings = embedBatch(batch)
            results.addAll(embeddings)
            offset += batchSize
        }

        return results
    }

    private suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        mutex.withLock {
            val inputArray = JsonArray(texts.map { kotlinx.serialization.json.JsonPrimitive(it) })
            val requestBody =
                JsonObject(
                    mapOf(
                        "input" to inputArray,
                        "model" to kotlinx.serialization.json.JsonPrimitive(model),
                    ),
                )

            val response =
                client.post("$baseUrl/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                    headers.append("Authorization", "Bearer $apiKey")
                }

            val responseBody = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val data = jsonResponse["data"]?.jsonArray ?: return emptyList()

            return data.map { item ->
                val embeddingArray = item.jsonObject["embedding"]?.jsonArray
                embeddingArray?.map { it.jsonPrimitive.float }?.toFloatArray() ?: FloatArray(dim)
            }
        }
}
