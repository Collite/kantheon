package org.tatrman.kantheon.hebe.memory.embeddings

class MockEmbeddingProvider(
    private val embeddingDim: Int = 1536,
) : EmbeddingProvider {
    override val model: String = "mock-embedding-model"
    override val dim: Int = embeddingDim

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text ->
            val hash = text.hashCode().toLong()
            val rngForText = java.util.Random(hash)
            FloatArray(dim) { rngForText.nextFloat() * 2f - 1f }
        }
}
