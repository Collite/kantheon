package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.ApprovalGate
import org.tatrman.kantheon.hebe.api.LeakDetector
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Receipts
import org.tatrman.kantheon.hebe.api.RiskLevel
import org.tatrman.kantheon.hebe.api.Span
import org.tatrman.kantheon.hebe.api.Tool
import org.tatrman.kantheon.hebe.api.ToolContext
import org.tatrman.kantheon.hebe.api.ToolResult
import org.tatrman.kantheon.hebe.api.ToolSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A restricted posture refusal must be a **receipted** state change (P2 Stage
 * 2.4 T1/T2) — the dispatcher writes a refusal receipt and never invokes the
 * blocked tool. (The mutation-funnel detekt rule enforces the receipt path.)
 */
class PostureDispatchReceiptTest {
    private val memory = mockk<MemoryStore>(relaxed = true)
    private val observer = mockk<Observer>(relaxed = true)
    private val receipts = mockk<Receipts>(relaxed = true)
    private val leakDetector =
        object : LeakDetector {
            override fun scan(result: ToolResult) = result
        }
    private val span = mockk<Span>(relaxed = true)
    private val ctx =
        mockk<ToolContext>(relaxed = true).also {
            every { it.sessionId } returns "s"
            every { it.turnId } returns "t"
            every { it.requestor } returns mockk { every { name } returns "cli" }
        }

    init {
        every { observer.span(any()) } returns span
        every { observer.event(any()) } returns Unit
        coEvery { receipts.append(any()) } returns 1L
    }

    private fun tool(name: String): Tool =
        mockk<Tool>().also {
            every { it.spec } returns ToolSpec(name, "d", JsonObject(emptyMap()))
            every { it.risk } returns RiskLevel.Low
            every { it.requiresApproval } returns false
            coEvery { it.invoke(any(), any()) } returns ToolResult.Ok(JsonPrimitive("ran"))
        }

    private fun dispatcher(
        tool: Tool,
        gate: PostureGate,
    ): ToolDispatcher {
        val registry = ToolRegistry().also { it.register(tool) }
        return ToolDispatcher(
            registry,
            emptyList(),
            mockk<ApprovalGate>(relaxed = true),
            memory,
            observer,
            leakDetector,
            receipts,
            gate,
        )
    }

    @Test
    fun `restricted refusal returns Err, writes a refusal receipt, and never invokes the tool`() =
        runTest {
            val git = tool("git")
            val d = dispatcher(git, PostureGate(ToolPosture.RESTRICTED))
            val outcome = d.dispatch(ParsedToolCall("id", "git", JsonObject(emptyMap())), ctx)
            val result = (outcome as DispatchOutcome.Result).result
            assertTrue(result is ToolResult.Err)
            assertTrue((result as ToolResult.Err).message.contains("posture"))
            // refusal is receipted, ok=false
            coVerify { receipts.append(match { it.tool == "git" && !it.ok }) }
            // the blocked tool was never run
            coVerify(exactly = 0) { git.invoke(any(), any()) }
        }

    @Test
    fun `enabled family under restricted runs the tool normally`() =
        runTest {
            val git = tool("git")
            val d = dispatcher(git, PostureGate(ToolPosture.RESTRICTED, enable = setOf("git")))
            val outcome = d.dispatch(ParsedToolCall("id", "git", JsonObject(emptyMap())), ctx)
            assertEquals(true, (outcome as DispatchOutcome.Result).result is ToolResult.Ok)
            coVerify { git.invoke(any(), any()) }
        }
}
