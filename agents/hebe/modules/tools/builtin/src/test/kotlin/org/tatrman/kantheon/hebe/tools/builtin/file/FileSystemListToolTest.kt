package org.tatrman.kantheon.hebe.tools.builtin.file

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.workspace.WorkspacePath
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileSystemListToolTest {
    @Test
    fun `list empty workspace returns empty array`(
        @TempDir tempDir: Path,
    ) {
        val tool = FileSystemListTool(WorkspaceFs(tempDir))
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(0, arr.size)
    }

    @Test
    fun `list returns entry per file with name and size`(
        @TempDir tempDir: Path,
    ) {
        val fs = WorkspaceFs(tempDir)
        fs.write(WorkspacePath("alpha.txt"), "hello")
        fs.write(WorkspacePath("beta.txt"), "world!")
        val tool = FileSystemListTool(fs)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Ok)
        val arr = (result as ToolResult.Ok).content as JsonArray
        assertEquals(2, arr.size)
        val names = arr.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("alpha.txt" in names)
        assertTrue("beta.txt" in names)
        val betaEntry = arr.first { (it as JsonObject)["name"]!!.jsonPrimitive.content == "beta.txt" } as JsonObject
        val size = betaEntry["size"]!!.jsonPrimitive.content.toLong()
        assertTrue(size > 0L, "size should be positive, got $size")
    }
}
