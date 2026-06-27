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
import org.tatrman.kantheon.hebe.api.ValidationResult
import org.tatrman.kantheon.hebe.api.Validator
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

class ToolDispatcherTest {
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
            every { it.sessionId } returns "sess1"
            every { it.turnId } returns "turn1"
            every { it.requestor } returns mockk { every { name } returns "cli" }
        }

    init {
        every { observer.span(any()) } returns span
        every { observer.event(any()) } returns Unit
        coEvery { receipts.append(any()) } returns 1L
    }

    private fun makeTool(
        name: String,
        risk: RiskLevel = RiskLevel.Low,
        result: ToolResult = ToolResult.Ok(JsonPrimitive("ok")),
    ): Tool {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name, "desc", JsonObject(emptyMap()))
        every { tool.risk } returns risk
        every { tool.requiresApproval } returns (risk == RiskLevel.High)
        coEvery { tool.invoke(any(), any()) } returns result
        return tool
    }

    private fun noopApprovalGate(): ApprovalGate = mockk(relaxed = true)

    private fun makeDispatcher(
        tool: Tool? = null,
        validators: List<Validator> = emptyList(),
        approvalGate: ApprovalGate = noopApprovalGate(),
    ): ToolDispatcher {
        val registry = ToolRegistry()
        tool?.let { registry.register(it) }
        return ToolDispatcher(
            registry,
            validators,
            approvalGate,
            memory,
            observer,
            leakDetector,
            receipts,
            PostureGate.unrestricted(),
        )
    }

    @Test
    fun `dispatch returns error for unknown tool`() =
        runTest {
            val dispatcher = makeDispatcher()
            val call = ParsedToolCall("id1", "nonexistent", JsonObject(emptyMap()))
            val outcome = dispatcher.dispatch(call, ctx)
            assertTrue(outcome is DispatchOutcome.Result)
            val result = (outcome as DispatchOutcome.Result).result
            assertTrue(result is ToolResult.Err)
            assertTrue((result as ToolResult.Err).message.contains("unknown tool"))
        }

    @Test
    fun `dispatch calls tool and returns Ok result`() =
        runTest {
            val tool = makeTool("echo")
            val dispatcher = makeDispatcher(tool)
            val call = ParsedToolCall("id1", "echo", JsonObject(emptyMap()))
            val outcome = dispatcher.dispatch(call, ctx)
            assertTrue(outcome is DispatchOutcome.Result)
            val result = (outcome as DispatchOutcome.Result).result
            assertTrue(result is ToolResult.Ok)
            coVerify { tool.invoke(any(), ctx) }
        }

    @Test
    fun `dispatch applies deny validator and returns error`() =
        runTest {
            val tool = makeTool("echo")
            val validator =
                object : Validator {
                    override suspend fun validate(
                        call: org.tatrman.kantheon.hebe.api.ParsedToolCall,
                        tool: org.tatrman.kantheon.hebe.api.Tool,
                        ctx: org.tatrman.kantheon.hebe.api.ToolContext,
                    ) = ValidationResult.Deny("policy violation")
                }
            val dispatcher = makeDispatcher(tool, validators = listOf(validator))
            val call = ParsedToolCall("id1", "echo", JsonObject(emptyMap()))
            val outcome = dispatcher.dispatch(call, ctx)
            val result = (outcome as DispatchOutcome.Result).result
            assertTrue(result is ToolResult.Err)
            assertTrue((result as ToolResult.Err).message.contains("policy"))
        }

    @Test
    fun `dispatch writes receipt on success`() =
        runTest {
            val tool = makeTool("echo")
            val dispatcher = makeDispatcher(tool)
            val call = ParsedToolCall("id1", "echo", JsonObject(emptyMap()))
            dispatcher.dispatch(call, ctx)
            coVerify { receipts.append(match { it.tool == "echo" && it.ok }) }
        }

    @Test
    fun `dispatch tool exception returns Err result`() =
        runTest {
            val tool = mockk<Tool>()
            every { tool.spec } returns ToolSpec("boom", "desc", JsonObject(emptyMap()))
            every { tool.risk } returns RiskLevel.Low
            every { tool.requiresApproval } returns false
            coEvery { tool.invoke(any(), any()) } throws RuntimeException("kaboom")
            val dispatcher = makeDispatcher(tool)
            val call = ParsedToolCall("id1", "boom", JsonObject(emptyMap()))
            val outcome = dispatcher.dispatch(call, ctx)
            val result = (outcome as DispatchOutcome.Result).result
            assertTrue(result is ToolResult.Err)
            assertEquals("tool exception: kaboom", (result as ToolResult.Err).message)
        }
}
