package org.tatrman.kantheon.hebe.tools.builtin.memory

import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryTreeToolTest {
    @Test
    fun `tree returns docs under prefix`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.listDocs("docs") } returns listOf("docs/faq", "docs/guide")
        val tool = MemoryTreeTool(memory)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("prefix", JsonPrimitive("docs")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(2, arr.size)
        val paths = arr.map { it.jsonPrimitive.content }.toSet()
        assertTrue("docs/faq" in paths)
        assertTrue("docs/guide" in paths)
    }

    @Test
    fun `tree with no prefix returns all docs`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.listDocs("") } returns listOf("notes/a", "notes/b", "wiki/home")
        val tool = MemoryTreeTool(memory)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(3, arr.size)
    }
}
