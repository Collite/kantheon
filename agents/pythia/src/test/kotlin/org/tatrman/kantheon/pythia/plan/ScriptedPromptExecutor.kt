package org.tatrman.kantheon.pythia.plan

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A Koog [PromptExecutor] that returns scripted assistant replies in order (the
 * last reply repeats once the script is exhausted). The scripted-LLM fixture for
 * planner/synth specs — no live calls (planning-conventions §4).
 */
class ScriptedPromptExecutor(
    private val replies: List<String>,
) : PromptExecutor() {
    var callCount: Int = 0
        private set

    /** The rendered text of every prompt seen, in call order (lets specs assert injected context). */
    val capturedPrompts: MutableList<String> = mutableListOf()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        capturedPrompts += prompt.messages.joinToString("\n") { it.content }
        val content = replies.getOrElse(callCount) { replies.last() }
        callCount++
        return listOf(
            Message.Assistant(
                content = content,
                metaInfo =
                    ResponseMetaInfo(
                        timestamp =
                            kotlin.time.Clock.System
                                .now(),
                    ),
            ),
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = flow { throw UnsupportedOperationException("scripted executor has no streaming") }

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = throw UnsupportedOperationException("scripted executor has no moderation")

    override fun close() {}
}
