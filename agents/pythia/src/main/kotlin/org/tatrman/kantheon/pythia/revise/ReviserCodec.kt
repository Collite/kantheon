package org.tatrman.kantheon.pythia.revise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.pythia.plan.PlanDecodeException
import org.tatrman.kantheon.pythia.v1.DisplayPriority
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.RevisionKind

/** A reviser action decoded from the STRONG reply. */
data class ReviserDecision(
    val kind: RevisionKind,
    val affectedHypIds: List<String>,
    val newHypotheses: List<Hypothesis>,
    val rationale: String,
)

/** Decodes a reviser reply `{ action, affectedHypIds, newHypotheses, rationale }` (codec idiom). */
object ReviserCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): ReviserDecision {
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
            }.getOrElse { throw PlanDecodeException("reviser reply is not a JSON object: ${it.message}") }
        val kind =
            when (obj["action"]?.jsonPrimitive?.content?.uppercase()) {
                "PRUNE" -> RevisionKind.REVISION_PRUNE
                "PIVOT" -> RevisionKind.REVISION_PIVOT
                "DECOMPOSE" -> RevisionKind.REVISION_DECOMPOSE
                "HALT" -> RevisionKind.REVISION_HALT
                else -> throw PlanDecodeException("reviser action must be PRUNE|PIVOT|DECOMPOSE|HALT")
            }
        val affected = (obj["affectedHypIds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        val newHyps =
            (obj["newHypotheses"] as? JsonArray)?.map { decodeHypothesis(it.jsonObject) } ?: emptyList()
        return ReviserDecision(kind, affected, newHyps, obj["rationale"]?.jsonPrimitive?.content ?: "")
    }

    private fun decodeHypothesis(o: JsonObject): Hypothesis {
        val h =
            Hypothesis
                .newBuilder()
                .setId(o["id"]?.jsonPrimitive?.content ?: throw PlanDecodeException("new hypothesis missing id"))
                .setStatement(o["statement"]?.jsonPrimitive?.content ?: "")
                .setStatus(HypStatus.HYP_PROPOSED)
                .setDisplayPriority(DisplayPriority.DISPLAY_SECONDARY)
        o["parentId"]?.jsonPrimitive?.content?.let(h::setParentId)
        o["estimatedExplanatoryPower"]
            ?.jsonPrimitive
            ?.content
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0)
            ?.let(h::setEstimatedExplanatoryPower)
        (o["predicate"] as? JsonObject)?.let { h.setPredicate(decodePredicate(it)) }
        return h.build()
    }

    private fun decodePredicate(o: JsonObject): org.tatrman.kantheon.pythia.v1.Predicate =
        org.tatrman.kantheon.pythia.v1.Predicate
            .newBuilder()
            .setKind(
                runCatching {
                    org.tatrman.kantheon.pythia.v1.Predicate.Kind
                        .valueOf(o["kind"]?.jsonPrimitive?.content ?: "PREDICATE_KIND_UNSPECIFIED")
                }.getOrDefault(org.tatrman.kantheon.pythia.v1.Predicate.Kind.PREDICATE_KIND_UNSPECIFIED),
            ).setThreshold(
                o["threshold"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: 0.0,
            ).setParametersJson(o["parametersJson"]?.jsonPrimitive?.content ?: "{}")
            .build()
}
