package org.tatrman.kantheon.hebe.memory.embeddings

import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CachedEmbeddingProvider(
    private val delegate: EmbeddingProvider,
    private val capacity: Int = 4096,
) : EmbeddingProvider {
    private companion object {
        const val DEFAULT_LOAD_FACTOR = 0.75f
        const val HASH_PREFIX_BYTES = 16
    }

    private val cache =
        object : LinkedHashMap<String, FloatArray>(capacity, DEFAULT_LOAD_FACTOR, true) {
            @Suppress("LongParameterList", "MaxLineLength")
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > capacity
        }
    private val mutex = Mutex()

    override val model: String = delegate.model
    override val dim: Int = delegate.dim

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val results = mutableListOf<FloatArray>()
        val uncached = mutableListOf<Int>()

        mutex.withLock {
            for (text in texts) {
                val key = hashKey(text)
                cache[key]?.let { results.add(it) } ?: run {
                    uncached.add(results.size)
                    results.add(FloatArray(dim))
                }
            }
        }

        if (uncached.isEmpty()) return results

        val freshEmbeddings = delegate.embed(uncached.map { texts[it] })

        mutex.withLock {
            for ((i, textIdx) in uncached.withIndex()) {
                val key = hashKey(texts[textIdx])
                cache[key] = freshEmbeddings[i]
            }
        }

        for ((resultIdx, embeddingIdx) in uncached.withIndex()) {
            results[embeddingIdx] = freshEmbeddings[resultIdx]
        }

        return results
    }

    private fun hashKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.copyOf(HASH_PREFIX_BYTES).toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder()
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }
}
