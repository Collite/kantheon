package org.tatrman.kantheon.envelope.render.catalog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Clock
import org.tatrman.kantheon.envelope.render.fallback.FormatCatalog

/**
 * A scripted Koog [PromptExecutor] (the same mock-executor shape the Themis
 * skeleton spec uses) — returns one [Message.Assistant] per call from a list,
 * capturing the rendered user prompt so the repair-feedback path can be asserted.
 */
private class ScriptedExecutor(
    private vararg val replies: String,
) : PromptExecutor() {
    val userPrompts = mutableListOf<String>()
    private var i = 0

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        userPrompts +=
            prompt.messages
                .filterIsInstance<Message.User>()
                .flatMap { it.parts }
                .filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
        val reply = replies.getOrElse(i) { replies.last() }
        i++
        return listOf(Message.Assistant(content = reply, metaInfo = ResponseMetaInfo(timestamp = Clock.System.now())))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = throw UnsupportedOperationException()

    override fun close() = Unit
}

private val cheap = LLModel(LLMProvider.Anthropic, "claude-haiku-3-5")

class KoogStructuredFormatterSpec :
    StringSpec({

        "round-trips a table tool call from the executor through the codec" {
            val exec =
                ScriptedExecutor(
                    """{"tool":"RenderTable","content":[{"A":1}],"details":{}}""",
                )
            val call = KoogStructuredFormatter(exec, cheap).pick(FormatRequest("q", "a"), priorError = null)
            call.shouldBeInstanceOf<RenderCall.Table>()
        }

        "a malformed first reply is retried by FormatCatalog with the prior error fed into the prompt" {
            val exec =
                ScriptedExecutor(
                    "not json", // attempt 1 → SCHEMA_INVALID
                    """{"tool":"RenderPlaintext","text":"recovered"}""", // attempt 2 → ok
                )
            val catalog = FormatCatalog(KoogStructuredFormatter(exec, cheap))

            val r = catalog.format(FormatRequest("q", "a"))

            r.text shouldBe "recovered"
            // The repair prompt on the 2nd call echoes the prior failure.
            exec.userPrompts[1].shouldContain("Your previous reply failed")
        }

        "an executor exception surfaces as LLM_ERROR and exhausts to the deterministic fallback" {
            val boom =
                object : PromptExecutor() {
                    override suspend fun execute(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>,
                    ): List<Message.Response> = throw RuntimeException("gateway down")

                    override fun executeStreaming(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>,
                    ): Flow<StreamFrame> = emptyFlow()

                    override suspend fun moderate(
                        prompt: Prompt,
                        model: LLModel,
                    ): ModerationResult = throw UnsupportedOperationException()

                    override fun close() = Unit
                }

            val r =
                FormatCatalog(KoogStructuredFormatter(boom, cheap), maxRetries = 1)
                    .format(FormatRequest("q", "the answer"))

            r.fellBack.shouldBeTrue()
            r.text shouldBe "the answer" // no structure → plaintext fallback
        }
    })
