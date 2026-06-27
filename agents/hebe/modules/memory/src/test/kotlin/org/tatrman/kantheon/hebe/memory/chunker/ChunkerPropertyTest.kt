package org.tatrman.kantheon.hebe.memory.chunker

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChunkerPropertyTest {
    @Test
    fun `output is deterministic`() {
        val text = "This is a test document. ".repeat(100)
        val chunks1 = Chunker.chunk(text)
        val chunks2 = Chunker.chunk(text)
        chunks1.map { it.content } shouldBe chunks2.map { it.content }
    }

    @Test
    fun `all tokens appear in some chunk`() {
        val text = "word ".repeat(500)
        val tokens =
            text
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .toSet()
        val allChunkTokens =
            Chunker.chunk(text).flatMap {
                it.content
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        allChunkTokens shouldContainAll tokens
    }

    @Test
    fun `no chunk exceeds targetWords plus overlap`() {
        val text = "word ".repeat(2000)
        val chunks = Chunker.chunk(text, ChunkerConfig(targetWords = 800))
        val maxWords = chunks.maxOfOrNull { it.tokenCount } ?: 0
        maxWords.shouldBeLessThan(900)
    }

    @Test
    fun `last chunk is at least minWords unless doc is below minWords`() {
        val longText = "word ".repeat(200)
        val chunks = Chunker.chunk(longText, ChunkerConfig(minWords = 50))
        val last = chunks.lastOrNull()
        if (chunks.size > 1) {
            last!!.tokenCount shouldBeGreaterThan 49
        }
    }

    @Test
    fun `chunk indices are contiguous from 0`() {
        val text = "word ".repeat(500)
        val chunks = Chunker.chunk(text)
        val indices = chunks.map { it.index }.toSet()
        indices shouldContainAll (0 until chunks.size).toSet()
    }
}
