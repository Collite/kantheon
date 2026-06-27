package org.tatrman.kantheon.themis.cache

import org.tatrman.kantheon.themis.config.CacheConfig
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

class ResolverCache(
    config: CacheConfig,
) {
    private val nlpCache =
        Caffeine
            .newBuilder()
            .maximumSize(config.nlpLruSize.toLong())
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build<String, NlpCacheEntry>()

    private val resolutionCache =
        Caffeine
            .newBuilder()
            .maximumSize(config.resolutionLruSize.toLong())
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build<String, ResolutionCacheEntry>()

    data class NlpCacheEntry(
        val text: String,
        val lang: String,
        val ops: String,
        val language: String,
        val languageConfidence: Double,
        val engineUsed: String,
        val tokens: String,
        val sentences: String,
        val paragraphs: String,
        val entities: String,
        val traceId: String,
        val elapsedMs: Long,
        val messages: String,
    )

    data class ResolutionCacheEntry(
        val question: String,
        val registryVersion: String,
        val contextHash: String,
        val responseJson: String,
    )

    fun nlpCacheKey(
        text: String,
        lang: String,
        ops: String,
    ): String = "$lang|$ops|${text.hashCode()}"

    fun resolutionCacheKey(
        question: String,
        registryVersion: String,
        contextHash: String,
    ): String = "$registryVersion|$contextHash|${question.hashCode()}"

    fun getNlpCached(key: String): NlpCacheEntry? = nlpCache.getIfPresent(key)

    fun putNlpCached(
        key: String,
        entry: NlpCacheEntry,
    ) {
        nlpCache.put(key, entry)
    }

    fun getResolutionCached(key: String): ResolutionCacheEntry? = resolutionCache.getIfPresent(key)

    fun putResolutionCached(
        key: String,
        entry: ResolutionCacheEntry,
    ) {
        resolutionCache.put(key, entry)
    }

    fun nlpStats() = nlpCache.stats()

    fun resolutionStats() = resolutionCache.stats()
}
