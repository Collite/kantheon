package org.tatrman.pinakes.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** Outcome of a `LoadApi` write — the corpus ids Kallimachos minted. */
data class LoadOutcome(
    val sourceId: Long,
    val partIds: List<Long>,
)

/**
 * Pinakes → Kallimachos internal write path (the `LoadApi`, cluster-internal).
 * The mechanical pipeline's LOAD stage calls [loadSource] with parts Pinakes has
 * already extracted + chunked. [ensureNotebook] makes the feed's mart exist so
 * the loaded source is queryable (the stage→warehouse proof).
 */
interface KallimachosWriteClient {
    suspend fun ensureNotebook(
        notebookId: String,
        displayName: String,
    )

    suspend fun loadSource(
        notebookId: String,
        title: String,
        mimeType: String,
        assetRef: String,
        parts: List<String>,
    ): LoadOutcome

    /** Trigger the warehouse to embed a loaded source (the EMBED stage). */
    suspend fun embedSource(sourceId: Long)
}

@Serializable
private data class NotebookCreateBody(
    val id: String,
    val displayName: String,
)

@Serializable
private data class LoadSourceBody(
    val notebookId: String,
    val title: String,
    val mimeType: String,
    val assetRef: String,
    val parts: List<String>,
)

@Serializable
private data class LoadSourceResult(
    val sourceId: Long,
    val partCount: Int,
    val partIds: List<Long>,
)

/**
 * HTTP impl over the Kallimachos REST surface (`{baseUrl}/load/source`,
 * `{baseUrl}/notebooks`). Integration-verified against a live Kallimachos.
 */
class HttpKallimachosWriteClient(
    private val http: HttpClient,
    private val baseUrl: String,
) : KallimachosWriteClient {
    override suspend fun ensureNotebook(
        notebookId: String,
        displayName: String,
    ) {
        // Idempotent create with an explicit id (re-creating a feed mart keeps its
        // members in the in-memory profile; the live profile no-ops on conflict).
        http.post("$baseUrl/notebooks") {
            contentType(ContentType.Application.Json)
            setBody(NotebookCreateBody(notebookId, displayName))
        }
    }

    override suspend fun loadSource(
        notebookId: String,
        title: String,
        mimeType: String,
        assetRef: String,
        parts: List<String>,
    ): LoadOutcome {
        val resp: HttpResponse =
            http.post("$baseUrl/load/source") {
                contentType(ContentType.Application.Json)
                setBody(LoadSourceBody(notebookId, title, mimeType, assetRef, parts))
            }
        require(resp.status.isSuccess()) { "LoadApi /load/source failed: ${resp.status}" }
        val body: LoadSourceResult = resp.body()
        return LoadOutcome(body.sourceId, body.partIds)
    }

    override suspend fun embedSource(sourceId: Long) {
        http.post("$baseUrl/admin/embedSource/$sourceId")
    }
}
