package org.tatrman.kantheon.hebe.tools.builtin.shell

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ShellToolTest {
    @Test
    fun `shell echo hello returns Ok`(
        @TempDir tempDir: Path,
    ) {
        val tool = ShellTool(tempDir, null)

        val args =
            buildJsonObject {
                put("cmd", kotlinx.serialization.json.JsonPrimitive("echo hello"))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Ok, "expected Ok but got: $result")
        val ok = result as ToolResult.Ok
        Assertions.assertTrue(ok.content.toString().contains("hello"))
    }

    @Test
    fun `shell missing cmd returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = ShellTool(tempDir, null)

        val args = buildJsonObject { }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }

    @Test
    fun `shell non-zero exit returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = ShellTool(tempDir, null)

        val args =
            buildJsonObject {
                put("cmd", kotlinx.serialization.json.JsonPrimitive("exit 1"))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("exit 1"))
    }

    @Test
    fun `shell timeout returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = ShellTool(tempDir, null)

        val args =
            buildJsonObject {
                put("cmd", kotlinx.serialization.json.JsonPrimitive("sleep 10"))
                put("timeout_ms", kotlinx.serialization.json.JsonPrimitive(100))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("timeout"))
    }
}
