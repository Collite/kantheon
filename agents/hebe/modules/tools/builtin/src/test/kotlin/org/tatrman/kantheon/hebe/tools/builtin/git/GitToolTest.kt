package org.tatrman.kantheon.hebe.tools.builtin.git

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitToolTest {
    private fun initRepo(dir: Path): Git {
        val git = Git.init().setDirectory(dir.toFile()).call()
        val config = git.repository.config
        config.setString("user", null, "name", "Test")
        config.setString("user", null, "email", "test@test.com")
        config.save()
        return git
    }

    @Test
    fun `status on clean repo returns clean=true`(
        @TempDir tempDir: Path,
    ) {
        initRepo(tempDir)
        val tool = GitTool(tempDir)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("status")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content as JsonObject
        assertTrue(obj["clean"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `status shows untracked file`(
        @TempDir tempDir: Path,
    ) {
        initRepo(tempDir)
        Files.writeString(tempDir.resolve("untracked.txt"), "hello")
        val tool = GitTool(tempDir)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("status")) }, ctx)
            }

        assertTrue(result is ToolResult.Ok)
        val obj = (result as ToolResult.Ok).content.toString()
        assertTrue(obj.contains("untracked.txt"))
    }

    @Test
    fun `commit creates a log entry`(
        @TempDir tempDir: Path,
    ) {
        initRepo(tempDir)
        Files.writeString(tempDir.resolve("file.txt"), "content")
        val tool = GitTool(tempDir)
        val ctx = mockk<ToolContext>()

        val commitResult =
            runBlocking {
                tool.invoke(
                    buildJsonObject {
                        put("verb", JsonPrimitive("commit"))
                        put("message", JsonPrimitive("initial commit"))
                    },
                    ctx,
                )
            }
        assertTrue(commitResult is ToolResult.Ok, "commit failed: $commitResult")

        val logResult =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("log")) }, ctx)
            }
        assertTrue(logResult is ToolResult.Ok)
        assertTrue(logResult.toString().contains("initial commit"))
    }

    @Test
    fun `effectiveRequiresApproval is true only for clone`(
        @TempDir tempDir: Path,
    ) {
        val tool = GitTool(tempDir)
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("clone")) }))
        assertTrue(!tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("status")) }))
        assertTrue(!tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("commit")) }))
        assertTrue(!tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("log")) }))
    }

    @Test
    fun `unknown verb returns Err`(
        @TempDir tempDir: Path,
    ) {
        initRepo(tempDir)
        val tool = GitTool(tempDir)
        val ctx = mockk<ToolContext>()

        val result =
            runBlocking {
                tool.invoke(buildJsonObject { put("verb", JsonPrimitive("rebase")) }, ctx)
            }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("unknown git verb"))
    }
}
