package org.tatrman.kantheon.themis.koog

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Koog [LLModel] constants for the two tiers Themis uses. The id is the only
 * field that matters to [org.tatrman.kantheon.llm.client.LlmGatewayPromptExecutor.mapModelToGatewayKey]
 * — provider is set to Anthropic for honesty since the gateway routes to
 * Claude family models, but the field is informational only.
 */
object ThemisModels {
    /** CHEAP tier — used by `filterRelevantSpans`. Resolver originally hit `model=haiku`. */
    val Cheap: LLModel =
        LLModel(provider = LLMProvider.Anthropic, id = "claude-haiku")

    /** FAST tier — used by `jointInference`. Resolver originally hit `model=sonnet`. */
    val Fast: LLModel =
        LLModel(provider = LLMProvider.Anthropic, id = "claude-sonnet")
}

/**
 * Constructs the agent-level [AIAgentConfig] for the Themis graph.
 *
 * Themis nodes carry their own per-node prompts (the existing files under
 * `src/main/resources/prompts/`); the agent-level system prompt is empty.
 * `maxAgentIterations`
 * caps Koog's loop count — Themis is a linear graph so the practical bound is
 * the node count (~10) plus a comfortable safety margin.
 */
fun themisAgentConfig(model: LLModel = ThemisModels.Fast): AIAgentConfig =
    AIAgentConfig(
        prompt = prompt("themis") { /* per-node prompts; nothing at agent level */ },
        model = model,
        maxAgentIterations = 50,
    )

/** Convenience for callers that just want a fully-resolved empty prompt. */
internal fun emptyThemisPrompt(): Prompt = prompt("themis") { }
