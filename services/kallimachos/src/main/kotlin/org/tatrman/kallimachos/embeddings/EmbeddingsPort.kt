package org.tatrman.kallimachos.embeddings

/**
 * The embedding model egress (architecture §4/§11). One multilingual model for
 * the whole corpus — a conformed dimension. The Prometheus `EmbedText` client
 * (contracts §10) implements this; the doc-store `RemoteHttpEmbeddingsClient` is
 * the documented fallback (risks §14).
 */
interface EmbeddingsPort {
    suspend fun embed(texts: List<String>): EmbedResult
}

data class EmbedResult(
    val vectors: List<FloatArray>,
    val modelId: String,
    val modelVersion: String,
    val dimensions: Int,
)

/**
 * The conformed corpus embedding dimension (architecture §11). Declared once;
 * the `doc_vectors` column N and the pipeline `EmbedConfig` must agree with it —
 * a mismatch is a config error, not two coexisting spaces.
 */
data class EmbedConfig(
    val modelId: String,
    val modelVersion: String,
    val dimensions: Int,
)
