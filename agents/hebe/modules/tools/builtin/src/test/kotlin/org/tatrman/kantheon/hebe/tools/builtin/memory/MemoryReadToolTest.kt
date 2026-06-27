package org.tatrman.kantheon.hebe.tools.builtin.memory

import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryReadToolTest {
    @Test
    fun `read existing doc returns Ok with content`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.readDoc("docs/overview") } returns "# Overview\nHello."
        val tool = MemoryReadTool(memory)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("path", JsonPrimitive("docs/overview")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).content.toString().contains("Overview"))
    }

    @Test
    fun `read missing doc returns Err`() {
        val memory = mockk<MemoryStore>()
        coEvery { memory.readDoc(any()) } returns null
        val tool = MemoryReadTool(memory)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("path", JsonPrimitive("missing/doc")) }, ctx)
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not found"))
    }

    @Test
    fun `read missing path arg returns Err`() {
        val memory = mockk<MemoryStore>()
        val tool = MemoryReadTool(memory)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
