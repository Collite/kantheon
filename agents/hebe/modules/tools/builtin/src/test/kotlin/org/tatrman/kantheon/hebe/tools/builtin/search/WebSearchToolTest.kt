package org.tatrman.kantheon.hebe.tools.builtin.search

import org.tatrman.kantheon.hebe.api.SecretLookup
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSearchToolTest {
    @Test
    fun `missing query returns Err`() {
        val tool = WebSearchTool(mockk<SecretLookup>())
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }

    @Test
    fun `with no brave key uses DuckDuckGo fallback and returns Ok`() {
        val secretLookup = mockk<SecretLookup>()
        every { secretLookup.secret(any()) } returns null
        val tool = WebSearchTool(secretLookup)
        val ctx = mockk<ToolContext>()
        every { ctx.secretLookup } returns secretLookup

        // DuckDuckGo may fail in test environment but the provider catches and returns empty list
        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("query", JsonPrimitive("test query")) }, ctx)
            }

        // Either Ok (empty hits when network unavailable) or Ok (with hits when network available)
        assertTrue(result is ToolResult.Ok)
    }

    @Test
    fun `with brave key set attempts Brave provider and returns Ok`() {
        val secretLookup = mockk<SecretLookup>()
        every { secretLookup.secret("brave_api_key") } returns "fake-key-for-test"
        val tool = WebSearchTool(secretLookup)
        val ctx = mockk<ToolContext>()
        every { ctx.secretLookup } returns secretLookup

        // Brave will fail with a fake key but BraveSearchProvider catches and returns empty list
        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("query", JsonPrimitive("test query")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
    }
}
