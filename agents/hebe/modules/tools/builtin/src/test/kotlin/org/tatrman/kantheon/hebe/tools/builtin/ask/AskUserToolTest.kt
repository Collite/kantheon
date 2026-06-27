package org.tatrman.kantheon.hebe.tools.builtin.ask

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AskUserToolTest {
    @Test
    fun `general question returns NeedsApproval`() {
        val tool = AskUserTool()
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("question", JsonPrimitive("What is your name?")) }, ctx)
            }

        assertTrue(result is ToolResult.NeedsApproval)
        assertEquals("What is your name?", (result as ToolResult.NeedsApproval).prompt)
    }

    @Test
    fun `credential purpose without secretName returns Err`() {
        val tool = AskUserTool()
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("question", JsonPrimitive("Enter your API key"))
                        put("purpose", JsonPrimitive("credential"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("secretName required"))
    }

    @Test
    fun `credential purpose with secretName returns NeedsApproval with secretName in payload`() {
        val tool = AskUserTool()
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("question", JsonPrimitive("Enter your API key"))
                        put("purpose", JsonPrimitive("credential"))
                        put("secretName", JsonPrimitive("my_api_key"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.NeedsApproval)
        val na = result as ToolResult.NeedsApproval
        assertEquals("my_api_key", na.payload["secretName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing question returns Err`() {
        val tool = AskUserTool()
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
