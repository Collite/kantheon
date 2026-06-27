package org.tatrman.kantheon.hebe.memory.cache

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LruResponseCacheTest {
    @Test
    fun `hit returns cached value`() {
        val cache = LruResponseCache(capacity = 3)
        val key = "test-key"
        val value = CachedResponse("prompt", "[]", 100, 50)
        cache.put(key, value)
        val result = cache.get(key)
        result shouldBe value
    }

    @Test
    fun `miss returns null`() {
        val cache = LruResponseCache(capacity = 3)
        val result = cache.get("nonexistent")
        result shouldBe null
    }

    @Test
    fun `eviction removes LRU entry when over capacity`() {
        val cache = LruResponseCache(capacity = 2)
        cache.put("k1", CachedResponse("p1", "[]", 1, 1))
        cache.put("k2", CachedResponse("p2", "[]", 2, 2))
        cache.put("k3", CachedResponse("p3", "[]", 3, 3))
        val evicted = cache.get("k1")
        evicted shouldBe null
        val k2 = cache.get("k2")
        k2 shouldBe CachedResponse("p2", "[]", 2, 2)
    }
}
