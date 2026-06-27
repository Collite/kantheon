package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }
private val intentJson = Json { ignoreUnknownKeys = true }

/**
 * Phase 3 Stage 3.2 — body of `classifyIntentKind` (after `extractUniversal`).
 *
 * Rules first, cheap-LLM tie-break second:
 *  - 0 trigger matches → [Themis.IntentKind.PROCEDURAL] default ([IntentSource.RULE_DEFAULT]).
 *  - exactly 1 match → that intent, no LLM call ([IntentSource.RULE]).
 *  - ≥2 matches (tie) → CHEAP-tier LLM disambiguation ([IntentSource.LLM_FALLBACK]).
 *
 * Gateway failure falls back to the first matched candidate at confidence 0.5,
 * mirroring the stub-on-failure semantics of the other LLM nodes.
 */
enum class IntentSource { RULE, RULE_DEFAULT, LLM_FALLBACK }

data class IntentClassifyOutput(
    val intentKind: Themis.IntentKind,
    val confidence: Double,
    val source: IntentSource,
    val rationale: String,
)

suspend fun classifyIntent(
    tokens: List<NlpToken>,
    locale: String,
    rules: IntentKindRules,
    llm: LlmGatewayClient,
): IntentClassifyOutput {
    val lemmas = tokens.map { it.lemma }
    val texts = tokens.map { it.text }
    val matches =
        (rules.matchTriggers(lemmas, texts, locale) + rules.matchExtraSignals(tokens))

    return when {
        matches.isEmpty() ->
            IntentClassifyOutput(
                rules.default,
                1.0,
                IntentSource.RULE_DEFAULT,
                "no trigger; default ${rules.default}",
            )

        matches.size == 1 ->
            IntentClassifyOutput(
                matches.first(),
                1.0,
                IntentSource.RULE,
                "rule match: ${matches.first()}",
            )

        else -> {
            val prompt = buildIntentLlmPrompt(texts.joinToString(" "), matches, locale)
            val response = callIntentLlm(llm, prompt)
            val (kind, confidence) = parseIntentResponse(response, fallback = matches.first())
            IntentClassifyOutput(
                kind,
                confidence,
                IntentSource.LLM_FALLBACK,
                "llm tie-break among ${matches.joinToString(",")}",
            )
        }
    }
}

fun buildIntentLlmPrompt(
    question: String,
    candidates: Set<Themis.IntentKind>,
    locale: String,
): String =
    """
    |The question (locale=$locale) matched multiple analytical-intent rules.
    |Choose the single best intent for: "$question"
    |Candidates: ${candidates.joinToString(", ") { it.name }}
    |Definitions: PROCEDURAL = a direct lookup/listing; RCA = asks why/what caused;
    |FORECAST = asks for a prediction/projection; SIMULATION = asks a what-if scenario.
    |Return a JSON object: {"intentKind":"<one of the candidates>","confidence":0-1,"rationale":"..."}
    """.trimMargin()

fun parseIntentResponse(
    response: String,
    fallback: Themis.IntentKind,
): Pair<Themis.IntentKind, Double> {
    val cleaned =
        response
            .trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
    return try {
        val obj = intentJson.decodeFromString<JsonObject>(cleaned)
        val kind =
            obj["intentKind"]?.jsonPrimitive?.content?.let { name ->
                runCatching { Themis.IntentKind.valueOf(name) }.getOrNull()
            } ?: fallback
        val confidence = obj["confidence"]?.jsonPrimitive?.double ?: 0.5
        kind to confidence
    } catch (e: Exception) {
        logger.error(e) { "Failed to parse intent response: $response" }
        fallback to 0.5
    }
}

/** CHEAP-tier gateway call with fallback-on-failure (mirrors filterRelevantSpans). */
suspend fun callIntentLlm(
    llm: LlmGatewayClient,
    prompt: String,
): String =
    llm
        .complete(
            prompt = prompt,
            model = "haiku",
            temperature = 0.0,
        ).getOrElse {
            logger.error { "LLM intent tie-break failed: ${it.message}" }
            """{"intentKind":"PROCEDURAL","confidence":0.5,"rationale":"LLM unavailable: ${it.message}"}"""
        }

suspend fun classifyIntentKindStep(
    state: ResolverContext,
    rules: IntentKindRules,
    llm: LlmGatewayClient,
): ResolverContext {
    val output = classifyIntent(state.parseState.nlpResponse.tokens, state.locale, rules, llm)
    logger.debug { "classifyIntentKind → ${output.intentKind} (${output.source})" }
    org.tatrman.kantheon.themis.ResolverOtel.recordIntentKind( // Phase 3 Stage 3.6 (T4)
        kind = output.intentKind.name,
        llmFallback = output.source == IntentSource.LLM_FALLBACK,
    )
    return state.copy(
        parseState =
            state.parseState.copy(
                intentKind = output.intentKind,
                lastNode = "classifyIntentKind",
            ),
    )
}
