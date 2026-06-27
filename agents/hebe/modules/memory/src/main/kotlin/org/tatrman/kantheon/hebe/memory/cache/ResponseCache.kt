package org.tatrman.kantheon.hebe.memory.cache

import java.util.LinkedHashMap

data class CachedResponse(
    val text: String,
    val toolCallsJson: String,
    val tokensIn: Int,
    val tokensOut: Int,
)

interface ResponseCache {
    fun get(key: String): CachedResponse?

    fun put(
        key: String,
        value: CachedResponse,
    )

    fun invalidate(key: String)

    fun clear()
}

class LruResponseCache(
    private val capacity: Int = 256,
) : ResponseCache {
    private companion object {
        const val DEFAULT_LOAD_FACTOR = 0.75f
    }

    private val map =
        object : LinkedHashMap<String, CachedResponse>(capacity, DEFAULT_LOAD_FACTOR, true) {
            @Suppress("MaxLineLength")
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResponse>?): Boolean = size > capacity
        }

    override fun get(key: String): CachedResponse? = map.get(key)

    override fun put(
        key: String,
        value: CachedResponse,
    ) {
        map[key] = value
    }

    override fun invalidate(key: String) {
        map.remove(key)
    }

    override fun clear() {
        map.clear()
    }
}

fun responseCacheKey(
    model: String,
    systemPrompt: String,
    messagesJson: String,
    toolsJson: String,
    temperature: Double,
): String {
    val input = "$model|$systemPrompt|$messagesJson|$toolsJson|$temperature"
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray())
    return hash.copyOf(HASH_BYTES).toHexString()
}

private const val HASH_BYTES = 32

private fun ByteArray.toHexString(): String {
    val sb = StringBuilder()
    for (b in this) sb.append("%02x".format(b))
    return sb.toString()
}
