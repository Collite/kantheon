package org.tatrman.kantheon.golem.format

import kotlinx.coroutines.withTimeoutOrNull
import org.tatrman.kantheon.envelope.v1.PromptChip

/**
 * LLM chip top-up (`chips/llm_topup.py`). Fires a CHEAP completion to suggest a few
 * extra follow-up chips, but **only when fewer than `chipMinBeforeTopup` chips exist**
 * (default 2) and the feature is enabled. Failures (timeout / parse) yield `[]` and
 * never kill the turn — and the call is bounded by `chipTopupTimeoutMs` so a slow
 * gateway can't stall the render path for optional chips. The concrete LLM call is
 * injected as [complete] so the format path stays testable without a gateway; the
 * default is the disabled no-op.
 *
 * The suggested strings are model output that round-trips back as a one-click user
 * prompt, so each is length-capped and stripped of newlines/control content before it
 * becomes a chip (defence against a model emitting an imperative / injection payload).
 */
class LlmTopupChips(
    private val config: FormatConfig,
    private val complete: (suspend (prompt: String) -> String?)? = null,
) {
    /** Suggest top-up chips when the gate allows; returns `[]` otherwise or on any failure/timeout. */
    suspend fun derive(
        userText: String,
        existingChipCount: Int,
    ): List<PromptChip> {
        val call = complete
        if (!config.chipLlmTopupEnabled || call == null) return emptyList()
        if (existingChipCount >= config.chipMinBeforeTopup) return emptyList()
        val reply =
            runCatching {
                withTimeoutOrNull(config.chipTopupTimeoutMs) { call(promptFor(userText)) }
            }.getOrNull() ?: return emptyList()
        return parse(reply)
    }

    private fun promptFor(userText: String): String {
        // Neutralise the interpolated user text — strip quotes/newlines and cap length so a
        // crafted question can't break out of the prompt frame or balloon the cheap call.
        val safe =
            userText
                .replace('"', '\'')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(USER_TEXT_CAP)
        return "Navrhni 1–3 stručné navazující otázky pro uživatele k dotazu: \"$safe\". " +
            "Vrať JSON pole řetězců."
    }

    /** Parse the LLM reply — strip ```json fences, take the first 3 non-blank strings. */
    private fun parse(reply: String): List<PromptChip> {
        val cleaned =
            reply
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        val items =
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(cleaned)
                    .let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            }.getOrNull() ?: return emptyList()
        return items
            .map { it.replace(Regex("\\s+"), " ").trim().take(CHIP_TEXT_CAP) }
            .filter { it.isNotEmpty() }
            .take(3)
            .map {
                PromptChip
                    .newBuilder()
                    .setDisplay(it)
                    .setPrompt(it)
                    .setSource("llm_topup")
                    .build()
            }
    }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else null

    private companion object {
        const val USER_TEXT_CAP = 500
        const val CHIP_TEXT_CAP = 200
    }
}
