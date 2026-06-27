package org.tatrman.kantheon.hebe.tools.builtin.schedule

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
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

class ScheduleToolTest {
    private val ctx = mockk<ToolContext>()

    @Test
    fun `create with valid args returns Ok with id`() {
        val tool = ScheduleTool()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("create"))
                        put("name", JsonPrimitive("daily-report"))
                        put("cron", JsonPrimitive("0 8 * * *"))
                        put("body_kind", JsonPrimitive("skill"))
                        put("body_ref", JsonPrimitive("report"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertTrue(obj["id"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("daily-report", obj["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create with invalid cron returns Err`() {
        val tool = ScheduleTool()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("create"))
                        put("name", JsonPrimitive("bad"))
                        put("cron", JsonPrimitive("not-a-cron"))
                        put("body_kind", JsonPrimitive("skill"))
                        put("body_ref", JsonPrimitive("something"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("invalid cron"))
    }

    @Test
    fun `list returns empty array`() {
        val tool = ScheduleTool()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("list")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(0, arr.size)
    }

    @Test
    fun `disable and enable return id with enabled flag`() {
        val tool = ScheduleTool()

        val disable =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("disable"))
                        put("id", JsonPrimitive("r-1"))
                    },
                    ctx,
                )
            }
        assertTrue(disable is ToolResult.Ok)
        val disabledObj = (disable as ToolResult.Ok).content as JsonObject
        assertEquals("false", disabledObj["enabled"]!!.jsonPrimitive.content)

        val enable =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("enable"))
                        put("id", JsonPrimitive("r-1"))
                    },
                    ctx,
                )
            }
        assertTrue(enable is ToolResult.Ok)
        val enabledObj = (enable as ToolResult.Ok).content as JsonObject
        assertEquals("true", enabledObj["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete returns deleted=true`() {
        val tool = ScheduleTool()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("delete"))
                        put("id", JsonPrimitive("r-99"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertEquals("true", obj["deleted"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown verb returns Err`() {
        val tool = ScheduleTool()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("run")) }, ctx)
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("unknown verb"))
    }
}
