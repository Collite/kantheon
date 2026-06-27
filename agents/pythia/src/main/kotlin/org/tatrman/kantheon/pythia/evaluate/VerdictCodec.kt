package org.tatrman.kantheon.pythia.evaluate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.pythia.v1.HypStatus

/** A CHEAP-tier structured verdict (Stage 3.1 fallback). */
data class LlmVerdict(
    val status: HypStatus,
    val confidence: Double,
    val rationale: String,
)

/** Decodes a CHEAP evaluator reply `{ verdict, confidence, rationale }` (golem codec idiom). */
object VerdictCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): LlmVerdict {
        val obj =
            runCatching {
                json
                    .parseToJsonElement(
                        raw
                            .trim()
                            .removePrefix("```json")
                            .removePrefix("```")
                            .removeSuffix("```")
                            .trim(),
                    ).jsonObject
            }.getOrNull() ?: return LlmVerdict(HypStatus.HYP_INCONCLUSIVE, 0.5, "unparseable verdict")
        val status =
            when (obj["verdict"]?.jsonPrimitive?.content?.uppercase()) {
                "SUPPORTED", "HYP_SUPPORTED" -> HypStatus.HYP_SUPPORTED
                "REFUTED", "HYP_REFUTED" -> HypStatus.HYP_REFUTED
                else -> HypStatus.HYP_INCONCLUSIVE
            }
        // LLM-supplied confidence is untrusted: reject non-finite (`NaN`/`Infinity` both
        // parse via toDoubleOrNull) and clamp to [0,1] so it can't poison downstream rollups.
        val confidence =
            obj["confidence"]
                ?.jsonPrimitive
                ?.content
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0.0, 1.0)
                ?: 0.5
        val rationale = obj["rationale"]?.jsonPrimitive?.content ?: ""
        return LlmVerdict(status, confidence, rationale)
    }
}
