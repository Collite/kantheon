package org.tatrman.kantheon.hebe.tools.builtin.memory

import org.tatrman.kantheon.hebe.api.HitSource
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemorySearchToolTest {
    @Test
    fun `search returns hits`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.search(any(), any(), any(), any()) } returns
            listOf(
                MemoryHit("docs/faq", 0, "snippet text", 0.9, HitSource.Fts),
            )
        val tool = MemorySearchTool(memory)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("query", JsonPrimitive("faq")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(1, arr.size)
        val hit = arr[0] as JsonObject
        assertEquals("docs/faq", hit["docPath"]!!.jsonPrimitive.content)
        assertEquals("snippet text", hit["snippet"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search with no results returns empty array`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.search(any(), any(), any(), any()) } returns emptyList()
        val tool = MemorySearchTool(memory)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("query", JsonPrimitive("nothing")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(0, arr.size)
    }

    @Test
    fun `search missing query returns Err`() {
        val memory = mockk<MemoryStore>()
        val tool = MemorySearchTool(memory)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
