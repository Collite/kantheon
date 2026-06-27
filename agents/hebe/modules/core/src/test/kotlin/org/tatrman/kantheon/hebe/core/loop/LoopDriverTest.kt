package org.tatrman.kantheon.hebe.core.loop

import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.LoopSignal
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Reasoning
import org.tatrman.kantheon.hebe.api.ReasoningContext
import org.tatrman.kantheon.hebe.api.RespondOutput
import org.tatrman.kantheon.hebe.api.TextAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopDriverTest {
    private val reasoning = mockk<Reasoning>(relaxed = true)
    private val ctx = mockk<ReasoningContext>(relaxed = true)

    @Test
    fun `text response returns Response outcome`() =
        runTest {
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Continue
            coEvery { delegate.beforeLlmCall(any(), any()) } returns null
            coEvery { delegate.callLlm(any(), any()) } returns RespondOutput.TextOnly("hello")
            coEvery { delegate.handleTextResponse("hello") } returns TextAction.FinishWith

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 5))

            assertEquals(LoopOutcome.Response("hello"), result)
        }

    @Test
    fun `tool calls are executed then loop continues to text`() =
        runTest {
            val call = ParsedToolCall("id1", "tool", JsonObject(emptyMap()))
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Continue
            coEvery { delegate.beforeLlmCall(any(), any()) } returns null
            coEvery { delegate.callLlm(any(), any()) }
                .returnsMany(RespondOutput.WithToolCalls(listOf(call)), RespondOutput.TextOnly("done"))
            coEvery { delegate.executeToolCalls(any(), any()) } returns null
            coEvery { delegate.handleTextResponse("done") } returns TextAction.FinishWith

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 5))

            assertEquals(LoopOutcome.Response("done"), result)
            coVerify(exactly = 1) { delegate.executeToolCalls(listOf(call), ctx) }
        }

    @Test
    fun `max iterations returns MaxIterations`() =
        runTest {
            val call = ParsedToolCall("id1", "tool", JsonObject(emptyMap()))
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Continue
            coEvery { delegate.beforeLlmCall(any(), any()) } returns null
            coEvery { delegate.callLlm(any(), any()) } returns RespondOutput.WithToolCalls(listOf(call))
            coEvery { delegate.executeToolCalls(any(), any()) } returns null

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 3))

            assertEquals(LoopOutcome.MaxIterations, result)
        }

    @Test
    fun `estop signal stops loop`() =
        runTest {
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Estop

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 5))

            assertEquals(LoopOutcome.Stopped, result)
            coVerify(exactly = 0) { delegate.callLlm(any(), any()) }
        }

    @Test
    fun `beforeLlmCall abort propagates outcome`() =
        runTest {
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Continue
            coEvery { delegate.beforeLlmCall(any(), any()) } returns LoopOutcome.Failure("budget exceeded")

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 5))

            assertTrue(result is LoopOutcome.Failure)
            assertEquals("budget exceeded", (result as LoopOutcome.Failure).message)
            coVerify(exactly = 0) { delegate.callLlm(any(), any()) }
        }

    @Test
    fun `executeToolCalls abort propagates outcome`() =
        runTest {
            val call = ParsedToolCall("id1", "tool", JsonObject(emptyMap()))
            val delegate = mockk<LoopDelegate>(relaxed = true)
            coEvery { delegate.checkSignals() } returns LoopSignal.Continue
            coEvery { delegate.beforeLlmCall(any(), any()) } returns null
            coEvery { delegate.callLlm(any(), any()) } returns RespondOutput.WithToolCalls(listOf(call))
            coEvery { delegate.executeToolCalls(any(), any()) } returns LoopOutcome.Failure("tool error")

            val result = runAgenticLoop(delegate, reasoning, ctx, LoopConfig(maxIterations = 5))

            assertTrue(result is LoopOutcome.Failure)
        }
}
