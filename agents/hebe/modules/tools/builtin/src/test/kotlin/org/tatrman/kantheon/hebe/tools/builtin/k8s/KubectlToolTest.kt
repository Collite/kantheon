package org.tatrman.kantheon.hebe.tools.builtin.k8s

import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KubectlToolTest {
    @Test
    fun `effectiveRequiresApproval is false for read-only verbs`(
        @TempDir tempDir: Path,
    ) {
        val tool = KubectlTool(tempDir)
        assertFalse(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("get")) }))
        assertFalse(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("describe")) }))
        assertFalse(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("logs")) }))
    }

    @Test
    fun `effectiveRequiresApproval is true for mutating verbs`(
        @TempDir tempDir: Path,
    ) {
        val tool = KubectlTool(tempDir)
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("apply")) }))
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("delete")) }))
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("create")) }))
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject { put("verb", JsonPrimitive("exec")) }))
    }

    @Test
    fun `effectiveRequiresApproval is true when verb is missing`(
        @TempDir tempDir: Path,
    ) {
        val tool = KubectlTool(tempDir)
        assertTrue(tool.effectiveRequiresApproval(buildJsonObject {}))
    }

    @Test
    fun `invoke missing verb returns Err`(
        @TempDir tempDir: Path,
    ) {
        val tool = KubectlTool(tempDir)
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(buildJsonObject {}, ctx) }

        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }
}
