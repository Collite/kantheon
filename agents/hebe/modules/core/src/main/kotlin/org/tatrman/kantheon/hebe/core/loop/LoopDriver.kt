package org.tatrman.kantheon.hebe.core.loop

import org.tatrman.kantheon.hebe.api.LoopConfig
import org.tatrman.kantheon.hebe.api.LoopOutcome
import org.tatrman.kantheon.hebe.api.LoopSignal
import org.tatrman.kantheon.hebe.api.ParsedToolCall
import org.tatrman.kantheon.hebe.api.Reasoning
import org.tatrman.kantheon.hebe.api.ReasoningContext
import org.tatrman.kantheon.hebe.api.RespondOutput
import org.tatrman.kantheon.hebe.api.TextAction
import org.slf4j.LoggerFactory

suspend fun runAgenticLoop(
    delegate: LoopDelegate,
    reasoning: Reasoning,
    ctx: ReasoningContext,
    config: LoopConfig,
): LoopOutcome {
    val logger = LoggerFactory.getLogger("org.tatrman.kantheon.hebe.core.loop.LoopDriver")

    for (iter in 0 until config.maxIterations) {
        val signal = delegate.checkSignals()
        if (signal != LoopSignal.Continue) {
            logger.debug("loop stopped by signal={} at iter={}", signal, iter)
            return LoopOutcome.Stopped
        }

        val beforeOutcome = delegate.beforeLlmCall(ctx, iter)
        if (beforeOutcome != null) {
            logger.debug("loop stopped by beforeLlmCall outcome={} at iter={}", beforeOutcome, iter)
            return beforeOutcome
        }

        val output = delegate.callLlm(reasoning, ctx)

        when (output) {
            is RespondOutput.TextOnly -> {
                when (delegate.handleTextResponse(output.text)) {
                    TextAction.FinishWith -> return LoopOutcome.Response(output.text)
                    TextAction.ContinueLoop -> { /* continue */ }
                }
            }
            is RespondOutput.WithToolCalls -> {
                val toolOutcome = delegate.executeToolCalls(output.calls, ctx)
                if (toolOutcome != null) {
                    logger.debug("loop stopped by executeToolCalls outcome={} at iter={}", toolOutcome, iter)
                    return toolOutcome
                }
            }
        }

        delegate.afterIteration(iter)
    }

    logger.debug("loop stopped: max iterations ({}) reached", config.maxIterations)
    return LoopOutcome.MaxIterations
}

interface LoopDelegate {
    suspend fun checkSignals(): LoopSignal

    suspend fun beforeLlmCall(
        ctx: ReasoningContext,
        iter: Int,
    ): LoopOutcome?

    suspend fun callLlm(
        reasoning: Reasoning,
        ctx: ReasoningContext,
    ): RespondOutput

    suspend fun handleTextResponse(text: String): TextAction

    suspend fun executeToolCalls(
        calls: List<ParsedToolCall>,
        ctx: ReasoningContext,
    ): LoopOutcome?

    suspend fun afterIteration(iter: Int)
}
