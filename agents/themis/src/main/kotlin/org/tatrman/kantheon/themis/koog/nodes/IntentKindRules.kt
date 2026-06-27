package org.tatrman.kantheon.themis.koog.nodes

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }

/**
 * Phase 3 Stage 3.2 — the rule layer of `classifyIntentKind`.
 *
 * Triggers are matched as contiguous word sequences over the parse's token
 * **lemmas** and (as a fallback for base/inflected forms the lemmatiser may not
 * normalise) the raw **text**. `extra_signals` are computed from UD morphology
 * (`feats`). The rules are living config — Czech/English coverage grows from
 * eval-corpus disagreements; see `prompts/intent_kind_rules.yaml`.
 */
data class IntentRule(
    val intent: Themis.IntentKind,
    /** lang ("cs"/"en") → trigger phrases (each a whitespace-delimited word sequence). */
    val triggers: Map<String, List<String>> = emptyMap(),
    val extraSignals: List<String> = emptyList(),
    val isDefault: Boolean = false,
)

class IntentKindRules(
    val rules: List<IntentRule>,
) {
    val default: Themis.IntentKind =
        rules.firstOrNull { it.isDefault }?.intent ?: Themis.IntentKind.PROCEDURAL

    /**
     * Intents whose triggers fire for the given lemmas/texts in [lang]. Matches
     * against both lemmas and raw text; an unknown [lang] falls back to matching
     * every language's triggers.
     */
    fun matchTriggers(
        lemmas: List<String>,
        texts: List<String>,
        lang: String,
    ): Set<Themis.IntentKind> {
        val loweredLemmas = lemmas.map { it.lowercase() }
        val loweredTexts = texts.map { it.lowercase() }
        return rules
            .asSequence()
            .filterNot { it.isDefault }
            .filter { rule ->
                triggerPhrasesFor(rule, lang).any { phrase ->
                    val seq = phrase.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                    loweredLemmas.containsSequence(seq) || loweredTexts.containsSequence(seq)
                }
            }.map { it.intent }
            .toSet()
    }

    /**
     * Intents whose `extra_signals` fire, computed from UD morphology:
     *  - `future_tense_temporal_reference` / `explicit_future_date` → any token with `Tense=Fut`.
     *  - `hypothetical_conditional` → any token with `Mood=Cnd`.
     */
    fun matchExtraSignals(tokens: List<NlpToken>): Set<Themis.IntentKind> {
        val hasFutureTense = tokens.any { it.feats["Tense"] == "Fut" }
        val hasConditional = tokens.any { it.feats["Mood"] == "Cnd" }
        return rules
            .asSequence()
            .filterNot { it.isDefault }
            .filter { rule ->
                rule.extraSignals.any { signal ->
                    when (signal) {
                        "future_tense_temporal_reference", "explicit_future_date" -> hasFutureTense
                        "hypothetical_conditional" -> hasConditional
                        else -> false
                    }
                }
            }.map { it.intent }
            .toSet()
    }

    private fun triggerPhrasesFor(
        rule: IntentRule,
        lang: String,
    ): List<String> =
        rule.triggers[lang]
            ?: rule.triggers[lang.substringBefore('-')]
            ?: rule.triggers.values.flatten() // unknown lang → match any language's triggers

    companion object {
        private val mapper =
            ObjectMapper(YAMLFactory()).apply {
                registerModule(KotlinModule.Builder().build())
                propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }

        /** Load + parse `prompts/intent_kind_rules.yaml` from the classpath. */
        fun load(classpathResource: String = "prompts/intent_kind_rules.yaml"): IntentKindRules {
            val stream =
                IntentKindRules::class.java.classLoader.getResourceAsStream(classpathResource)
                    ?: run {
                        logger.warn { "No intent rules at classpath:$classpathResource — PROCEDURAL-only" }
                        return IntentKindRules(
                            listOf(IntentRule(Themis.IntentKind.PROCEDURAL, isDefault = true)),
                        )
                    }
            val manifest: RulesManifest = stream.use { mapper.readValue(it) }
            return IntentKindRules(manifest.rules.map { it.toRule() })
        }
    }
}

/** YAML-binding shapes (snake_case keys mapped via the SNAKE_CASE strategy). */
private data class RulesManifest(
    val rules: List<RuleManifest> = emptyList(),
)

private data class RuleManifest(
    val intent: String,
    val triggers: Map<String, List<String>> = emptyMap(),
    val operatesOn: String? = null,
    val extraSignals: List<String> = emptyList(),
    val isDefault: Boolean = false,
) {
    fun toRule(): IntentRule =
        IntentRule(
            intent = Themis.IntentKind.valueOf(intent),
            triggers = triggers,
            extraSignals = extraSignals,
            isDefault = isDefault,
        )
}

private fun List<String>.containsSequence(seq: List<String>): Boolean {
    if (seq.isEmpty() || seq.size > size) return false
    for (i in 0..(size - seq.size)) {
        if ((seq.indices).all { this[i + it] == seq[it] }) return true
    }
    return false
}
