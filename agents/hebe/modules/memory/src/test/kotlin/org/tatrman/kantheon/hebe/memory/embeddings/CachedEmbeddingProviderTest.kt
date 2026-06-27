package org.tatrman.kantheon.hebe.memory.embeddings

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CachedEmbeddingProviderTest {
    @Test
    fun `cache hit returns cached value`(): Unit =
        runBlocking {
            val delegate = mockk<EmbeddingProvider>()
            coEvery { delegate.embed(any()) } returns listOf(FloatArray(4) { 0f })
            coEvery { delegate.model } returns "mock"
            coEvery { delegate.dim } returns 4
            val cached = CachedEmbeddingProvider(delegate, capacity = 10)
            val text = "hello world"
            val first = cached.embed(listOf(text))
            val second = cached.embed(listOf(text))
            first[0] shouldBe second[0]
        }

    @Test
    fun `cache miss calls delegate`(): Unit =
        runBlocking {
            val delegate = mockk<EmbeddingProvider>()
            coEvery { delegate.embed(any()) } returns listOf(FloatArray(4) { 0f })
            coEvery { delegate.model } returns "mock"
            coEvery { delegate.dim } returns 4
            val cached = CachedEmbeddingProvider(delegate, capacity = 10)
            val text = "cache miss test"
            val result = cached.embed(listOf(text))
            result.size shouldBe 1
            result[0].size shouldBe 4
        }

    @Test
    fun `eviction of LRU entry causes re-fetch on next access`(): Unit =
        runBlocking {
            val delegate = mockk<EmbeddingProvider>(relaxed = true)
            coEvery { delegate.model } returns "mock"
            coEvery { delegate.dim } returns 4
            coEvery { delegate.embed(any()) } returns listOf(FloatArray(4) { 0f })

            val capacity = 3
            val cached = CachedEmbeddingProvider(delegate, capacity = capacity)

            // Fill the cache: text1, text2, text3 (text1 is now the LRU)
            cached.embed(listOf("text1"))
            cached.embed(listOf("text2"))
            cached.embed(listOf("text3"))

            // Adding text4 evicts text1 (LRU)
            cached.embed(listOf("text4"))

            // Re-requesting text1 must miss the cache and call the delegate again
            cached.embed(listOf("text1"))

            // delegate.embed(["text1"]) called twice: initial fill + after eviction
            coVerify(exactly = 2) { delegate.embed(listOf("text1")) }
        }
}
