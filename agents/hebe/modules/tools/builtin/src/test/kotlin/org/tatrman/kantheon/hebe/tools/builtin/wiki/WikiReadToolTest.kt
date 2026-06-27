package org.tatrman.kantheon.hebe.tools.builtin.wiki

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WikiReadToolTest {
    @Test
    fun `read existing wiki page returns content and slug`(
        @TempDir tempDir: Path,
    ) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("wiki/architecture.md"), "# Architecture\nOverview here.")
        val tool = WikiReadTool(fs)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("slug", JsonPrimitive("architecture")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertEquals("architecture", obj["slug"]!!.jsonPrimitive.content)
        assertTrue(obj["content"]!!.jsonPrimitive.content.contains("Architecture"))
    }

    @Test
    fun `read extracts wikilinks from content`(
        @TempDir tempDir: Path,
    ) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("wiki/guide.md"), "See [[setup]] and [[teardown]] for details.")
        val tool = WikiReadTool(fs)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("slug", JsonPrimitive("guide")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        val links = (obj["links"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertTrue("setup" in links)
        assertTrue("teardown" in links)
    }

    @Test
    fun `read missing wiki page returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = WikiReadTool(WorkspaceFs(tempDir))
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("slug", JsonPrimitive("nonexistent")) }, ctx)
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not found"))
    }
}
