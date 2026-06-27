package org.tatrman.kantheon.kleio.clients

/** A cited chunk returned by `library.getContext` (the retrieved set Kleio grounds on). */
data class RetrievedChunk(
    val partId: Long,
    val sourceId: Long,
    val pageId: Long?,
    val text: String,
    val score: Double,
    val title: String,
    val locator: String,
    val sourceRef: String,
)

/**
 * Kleio → kallimachos-mcp (`library.getContext`) with the caller's OBO bearer
 * (architecture §5/§8 — Kleio never uses service identity; the RLS edge scopes
 * the result to the caller's visible mart). The retrieved set is the ONLY thing
 * Kleio may cite (the grounding contract).
 */
interface KallimachosMcpClient {
    suspend fun getContext(
        notebookId: String,
        question: String,
        k: Int,
        bearer: String?,
    ): List<RetrievedChunk>
}
