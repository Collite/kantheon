package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSystemGlobToolTest {
    @Test
    fun `glob filters by extension`(
        @TempDir tempDir: Path,
    ) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("readme.md"), "# doc")
        fs.write(WorkspacePath("script.kt"), "fun main() {}")
        fs.write(WorkspacePath("notes.md"), "notes")
        val tool = FileSystemGlobTool(fs)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("pattern", JsonPrimitive("*.md")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(2, arr.size)
        val names = arr.map { it.jsonPrimitive.content }.toSet()
        assertTrue("readme.md" in names)
        assertTrue("notes.md" in names)
    }

    @Test
    fun `glob with no matches returns empty array`(
        @TempDir tempDir: Path,
    ) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("script.kt"), "code")
        val tool = FileSystemGlobTool(fs)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("pattern", JsonPrimitive("*.md")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(0, arr.size)
    }

    @Test
    fun `glob missing pattern returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = FileSystemGlobTool(WorkspaceFs(tempDir))
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
