package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.koog.InferenceResult
import org.tatrman.kantheon.themis.koog.ResolverContext

private val logger = KotlinLogging.logger { }
private val jointJson = Json { ignoreUnknownKeys = true }

/**
 * Stage 2.3 T5 — body of `jointInference` (FAST-tier LLM call).
 *
 * **Prompt parity invariant.** [buildJointInferencePrompt] reproduces the
 * ai-platform Resolver prompt byte-for-byte. Eval-gate (Stage 2.4) will catch
 * any drift; do not edit the template here.
 *
 * **Failure semantics preserved.** A gateway `Result.failure` returns a
 * stub JSON that decodes into `InferenceResult(confidence=0.0, rationale=…)`
 * — matches the Resolver's `callLlmFast` path so eval-gate traces don't
 * change shape on failure.
 */

fun buildJointInferencePrompt(state: ResolverContext): String {
    val functionSpecs = state.registry.functionSpecsList
    val parseText =
        state.parseState.nlpResponse.tokens
            .joinToString(" ") { it.text }
    val entities =
        state.parseState.universalEntities.joinToString(", ") { "${it.rawText}(${it.entityType.name})" }
    val functionsText =
        functionSpecs.joinToString("\n") { f ->
            "${f.functionId}: ${f.description}\n    params: ${f.paramsList.joinToString(
                ", ",
            ) { "${it.name}: ${it.type}" }}"
        }
    return """
        |Question: $parseText
        |Universal entities: $entities
        |Available functions:
        |$functionsText
        |Return a JSON object with: functionId, argsJson (camelCase keys), confidence (0-1), rationale.
        |Example: {"functionId":"listInvoices","argsJson":{"customerId":"CUST-001"},"confidence":0.95,"rationale":"..."}
        """.trimMargin()
}

fun parseJointInferenceResponse(response: String): InferenceResult {
    val cleaned =
        response
            .trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
    return try {
        val obj = jointJson.decodeFromString<JsonObject>(cleaned)
        InferenceResult(
            functionId = obj["functionId"]?.jsonPrimitive?.content ?: "",
            argsJson = obj["argsJson"]?.jsonPrimitive?.content ?: "{}",
            bindings = emptyList(),
            confidence = obj["confidence"]?.jsonPrimitive?.double ?: 0.0,
            alternatives = emptyList(),
            rationale = obj["rationale"]?.jsonPrimitive?.content ?: "",
        )
    } catch (e: Exception) {
        logger.error(e) { "Failed to parse joint inference response: $response" }
        InferenceResult(
            functionId = "",
            argsJson = "{}",
            bindings = emptyList(),
            confidence = 0.0,
            alternatives = emptyList(),
            rationale = "Parse error: ${e.message}",
        )
    }
}

/** FAST-tier gateway call with stub-on-failure semantics. Mirrors ai-platform
 *  Resolver's `callLlmFast` so eval-gate traces don't drift. */
suspend fun callJointInferenceLlm(
    llm: LlmGatewayClient,
    prompt: String,
): String =
    llm
        .complete(
            prompt = prompt,
            model = "sonnet",
            temperature = 0.0,
        ).getOrElse {
            logger.error { "LLM fast call failed: ${it.message}" }
            """{"functionId":"","argsJson":"{}","confidence":0.0,"rationale":"LLM unavailable: ${it.message}"}"""
        }

suspend fun jointInferenceStep(
    state: ResolverContext,
    llm: LlmGatewayClient,
): ResolverContext {
    val prompt = buildJointInferencePrompt(state)
    logger.debug { "jointInference prompt: $prompt" }
    val llmResponse = callJointInferenceLlm(llm, prompt)
    val inferenceResult = parseJointInferenceResponse(llmResponse)
    return state.copy(
        parseState =
            state.parseState.copy(
                inferenceResult = inferenceResult,
                lastNode = "jointInference",
            ),
    )
}
