package org.tatrman.kantheon.hebe.tools.builtin.git

import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitPushToolTest {
    @Test
    fun `push on non-git directory returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = GitPushTool(tempDir)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not a git repo"))
    }

    @Test
    fun `push has High risk`(
        @TempDir tempDir: Path,
    ) {
        assertEquals(RiskLevel.High, GitPushTool(tempDir).risk)
    }
}
