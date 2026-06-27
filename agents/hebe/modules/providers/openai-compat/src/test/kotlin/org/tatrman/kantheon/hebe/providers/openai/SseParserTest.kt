package org.tatrman.kantheon.hebe.providers.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SseParserTest {
    @Test
    fun `non-data line returns empty list`() {
        val result = SseParser.parseChunk("event: ping", mutableMapOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `DONE sentinel returns Done`() {
        val result = SseParser.parseChunk("data: [DONE]", mutableMapOf())
        assertEquals(listOf(SseParser.ParsedChunkPart.Done), result)
    }

    @Test
    fun `text delta parsed`() {
        val line = """data: {"delta":{"content":"hello"}}"""
        val result = SseParser.parseChunk(line, mutableMapOf())
        assertEquals(listOf(SseParser.ParsedChunkPart.TextDelta("hello")), result)
    }

    @Test
    fun `tool call accumulated across fragments and emitted when complete`() {
        val acc = mutableMapOf<Int, SseParser.AccumulatedToolCall>()

        val line1 = """data: {"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"file_read","arguments":""}}]}}"""
        val line2 = """data: {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}"""
        val line3 = """data: {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"README.md\"}"}}]}}"""

        val r1 = SseParser.parseChunk(line1, acc)
        val r2 = SseParser.parseChunk(line2, acc)
        val r3 = SseParser.parseChunk(line3, acc)

        assertTrue(r1.isEmpty() || r1.none { it is SseParser.ParsedChunkPart.ToolCallReady })
        assertTrue(r2.isEmpty() || r2.none { it is SseParser.ParsedChunkPart.ToolCallReady })

        val toolCallReady = (r1 + r2 + r3).filterIsInstance<SseParser.ParsedChunkPart.ToolCallReady>()
        assertEquals(1, toolCallReady.size)
        assertEquals("call_abc", toolCallReady[0].id)
        assertEquals("file_read", toolCallReady[0].name)
        assertTrue(toolCallReady[0].arguments.contains("README.md"))
    }

    @Test
    fun `recorded SSE stream emits TextDelta then ToolCall then Done`() {
        val stream =
            listOf(
                """data: {"delta":{"content":"Fetching file..."}}""",
                """data: {"delta":{"tool_calls":[{"index":0,"id":"tc1","function":{"name":"read_file","arguments":""}}]}}""",
                """data: {"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"foo.txt\"}"}}]}}""",
                "data: [DONE]",
            )

        val acc = mutableMapOf<Int, SseParser.AccumulatedToolCall>()
        val parts = stream.flatMap { SseParser.parseChunk(it, acc) }

        val textDeltas = parts.filterIsInstance<SseParser.ParsedChunkPart.TextDelta>()
        val toolCalls = parts.filterIsInstance<SseParser.ParsedChunkPart.ToolCallReady>()
        val dones = parts.filterIsInstance<SseParser.ParsedChunkPart.Done>()

        assertEquals(1, textDeltas.size)
        assertEquals("Fetching file...", textDeltas[0].text)
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls[0].name)
        assertEquals(1, dones.size)
    }
}
