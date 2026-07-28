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
        locale: String = "",
    ): List<PromptChip> {
        val call = complete
        if (!config.chipLlmTopupEnabled || call == null) return emptyList()
        if (existingChipCount >= config.chipMinBeforeTopup) return emptyList()
        val reply =
            runCatching {
                withTimeoutOrNull(config.chipTopupTimeoutMs) { call(promptFor(userText, locale)) }
            }.getOrNull() ?: return emptyList()
        return parse(reply)
    }

    /**
     * The top-up prompt, in the turn's language.
     *
     * These chips are user-facing text, so they must follow the turn's locale like every other
     * rendered string — unlike the plan prompt, which the Shem bundle selects by locale
     * (`prompts/<locale>/intent.yaml`), this one lives in code and used to be unconditionally
     * Czech. The visible symptom was an English session answering with an English caption and
     * Czech follow-up chips underneath it.
     *
     * `cs` keeps its original prompt verbatim — it is the estate's primary language and this
     * exact wording is the one proven in production. Every other locale gets an English frame
     * that names the target language explicitly, which covers the five the SPA offers (and any
     * later addition) without maintaining a translation per language. A blank locale means the
     * caller sent none, so it keeps the historical Czech behaviour rather than silently
     * switching an existing deployment's chips to English.
     */
    private fun promptFor(
        userText: String,
        locale: String,
    ): String {
        // Neutralise the interpolated user text — strip quotes/newlines and cap length so a
        // crafted question can't break out of the prompt frame or balloon the cheap call.
        val safe =
            userText
                .replace('"', '\'')
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(USER_TEXT_CAP)
        val code = locale.trim().lowercase().take(2)
        if (code.isEmpty() || code == "cs") {
            return "Navrhni 1–3 stručné navazující otázky pro uživatele k dotazu: \"$safe\". " +
                "Vrať JSON pole řetězců."
        }
        val language = LANGUAGE_NAMES[code] ?: code
        return "Suggest 1-3 short follow-up questions the user might ask next, given their " +
            "question: \"$safe\". Write them in $language. " +
            "Return only a JSON array of strings."
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

        /** The languages the Iris picker offers. An unlisted code is passed through as-is. */
        val LANGUAGE_NAMES =
            mapOf(
                "cs" to "Czech",
                "en" to "English",
                "de" to "German",
                "sk" to "Slovak",
                "hu" to "Hungarian",
            )
    }
}
