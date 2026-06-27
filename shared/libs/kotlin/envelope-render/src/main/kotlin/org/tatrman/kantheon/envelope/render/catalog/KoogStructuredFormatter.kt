package org.tatrman.kantheon.envelope.render.catalog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import kotlinx.coroutines.CancellationException
import org.tatrman.kantheon.envelope.render.fallback.StructuredFormatter

/**
 * A Koog-backed [StructuredFormatter] — the format-catalog spike's LLM binding.
 *
 * It runs one CHEAP-tier completion through a Koog [PromptExecutor], asking the
 * model to pick one render tool and reply with a discriminated-union JSON object,
 * then decodes it via [RenderCallCodec]. Retry, repair-error feedback and the
 * deterministic fallback are NOT here — [org.tatrman.kantheon.envelope.render.fallback.FormatCatalog]
 * owns them (LLM-agnostically), so this adapter is a thin, single-shot bridge.
 *
 * **Spike verdict (see module README).** Koog's `PromptExecutor` + `prompt {}`
 * DSL are the clean integration points (same as Themis). Koog's
 * `StructureFixingParser` is deliberately NOT used: it targets a single
 * JSON-object schema, whereas the catalog is a four-way `tool_choice="any"`
 * dispatch, and its repair loop would duplicate FormatCatalog's retry. The
 * discriminated-union object + our codec reproduces v2's tool-choice semantics
 * without it.
 */
class KoogStructuredFormatter(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val prompts: FormatPrompt = FormatPrompt(),
) : StructuredFormatter {
    override suspend fun pick(
        request: FormatRequest,
        priorError: String?,
    ): RenderCall {
        val p =
            prompt("format-catalog") {
                system(prompts.system())
                user(prompts.user(request, priorError))
            }

        val responses =
            try {
                executor.execute(p, model, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw FormatToolException(FormatToolException.Reason.LLM_ERROR, e.message ?: "LLM call failed")
            }

        val content =
            responses
                .filterIsInstance<Message.Assistant>()
                .joinToString("\n") { it.content }
                .ifBlank { throw FormatToolException(FormatToolException.Reason.NO_TOOL_CALL, "empty LLM reply") }

        return RenderCallCodec.parse(content)
    }
}
