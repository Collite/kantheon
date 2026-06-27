package org.tatrman.kantheon.hebe.tools.builtin.jobs

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JobToolsTest {
    private val ctx = mockk<ToolContext>()

    @Test
    fun `job_create returns pending status with id`() {
        val tool = JobCreateTool()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("kind", JsonPrimitive("adhoc"))
                        put("payload", JsonPrimitive("""{"task":"run"}"""))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertEquals("pending", obj["status"]!!.jsonPrimitive.content)
        assertTrue(obj["id"]!!.jsonPrimitive.content.startsWith("job-"))
    }

    @Test
    fun `job_create with invalid kind returns Err`() {
        val tool = JobCreateTool()

        val result =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("kind", JsonPrimitive("unknown-kind"))
                        put("payload", JsonPrimitive("{}"))
                    },
                    ctx,
                )
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("invalid kind"))
    }

    @Test
    fun `job_cancel returns cancelled status`() {
        val tool = JobCancelTool()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("id", JsonPrimitive("job-123")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertEquals("cancelled", obj["status"]!!.jsonPrimitive.content)
        assertEquals("job-123", obj["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `job_status returns pending for any id`() {
        val tool = JobStatusTool()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("id", JsonPrimitive("job-456")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertEquals("pending", obj["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `job_create missing kind returns Err`() {
        val tool = JobCreateTool()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("payload", JsonPrimitive("{}")) }, ctx)
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
