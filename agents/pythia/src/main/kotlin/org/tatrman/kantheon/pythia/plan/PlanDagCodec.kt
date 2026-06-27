package org.tatrman.kantheon.pythia.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.pythia.v1.DataDep
import org.tatrman.kantheon.pythia.v1.DataFrameNode
import org.tatrman.kantheon.pythia.v1.DisplayPriority
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.ModelNode
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.QueryNode
import org.tatrman.kantheon.pythia.v1.ReasoningNode
import org.tatrman.kantheon.pythia.v1.RenderNode

/** Thrown when a planner reply can't be decoded into a [PlanDag] (fed back into the retry). */
class PlanDecodeException(
    message: String,
) : RuntimeException(message)

/**
 * Decodes a planner LLM reply into a [PlanDag] (the JSON contract lives in the
 * planner prompt). Fence-stripping + manual field reads — the repo convention
 * (golem `MiniPlanCodec`), not Koog `StructureFixingParser`. Phase 2 accepts the
 * SQL node kinds (query / reasoning / render); Phase 4 adds dataframe / model.
 */
object PlanDagCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): PlanDag {
        val obj = parseObject(raw)
        val plan = PlanDag.newBuilder()
        obj["rationale"]?.jsonPrimitive?.content?.let(plan::setRationale)
        (obj["revision"]?.jsonPrimitive?.content?.toIntOrNull())?.let(plan::setRevision)
        obj["hypotheses"]?.let { hs ->
            (hs as? JsonArray)?.forEach { plan.addHypotheses(decodeHypothesis(it.jsonObject)) }
        }
        obj["nodes"]?.let { ns ->
            (ns as? JsonArray)?.forEach { plan.addNodes(decodeNode(it.jsonObject)) }
        }
        obj["edges"]?.let { es ->
            (es as? JsonArray)?.forEach { plan.addEdges(decodeEdge(it.jsonObject)) }
        }
        return plan.build()
    }

    private fun decodeHypothesis(o: JsonObject): Hypothesis {
        val h =
            Hypothesis
                .newBuilder()
                .setId(o.str("id") ?: throw PlanDecodeException("hypothesis missing id"))
                .setStatement(o.str("statement") ?: "")
                .setStatus(HypStatus.HYP_PROPOSED)
        o.str("parentId")?.let(h::setParentId)
        o.dbl("estimatedExplanatoryPower")?.let(h::setEstimatedExplanatoryPower)
        o.dbl("diagnosticPower")?.let(h::setDiagnosticPower)
        h.displayPriority =
            when (o.str("displayPriority")?.uppercase()) {
                "DISPLAY_HIDDEN", "HIDDEN" -> DisplayPriority.DISPLAY_HIDDEN
                "DISPLAY_SECONDARY", "SECONDARY" -> DisplayPriority.DISPLAY_SECONDARY
                "DISPLAY_PRIMARY", "PRIMARY" -> DisplayPriority.DISPLAY_PRIMARY
                else -> DisplayPriority.DISPLAY_SECONDARY
            }
        (o["predicate"] as? JsonObject)?.let { h.setPredicate(decodePredicate(it)) }
        return h.build()
    }

    private fun decodePredicate(o: JsonObject): org.tatrman.kantheon.pythia.v1.Predicate =
        org.tatrman.kantheon.pythia.v1.Predicate
            .newBuilder()
            .setKind(
                runCatching {
                    org.tatrman.kantheon.pythia.v1.Predicate.Kind
                        .valueOf(o.str("kind") ?: "PREDICATE_KIND_UNSPECIFIED")
                }.getOrDefault(org.tatrman.kantheon.pythia.v1.Predicate.Kind.PREDICATE_KIND_UNSPECIFIED),
            ).setThreshold(o.dbl("threshold") ?: 0.0)
            .setParametersJson(o.str("parametersJson") ?: "{}")
            .build()

    private fun decodeNode(o: JsonObject): PlanNode {
        val node =
            PlanNode
                .newBuilder()
                .setNodeId(o.str("nodeId") ?: throw PlanDecodeException("node missing nodeId"))
        (o["testsHypIds"] as? JsonArray)?.forEach { node.addTestsHypIds(it.jsonPrimitive.content) }
        when (o.str("kind")?.lowercase()) {
            "query" ->
                node.setQuery(
                    QueryNode
                        .newBuilder()
                        .setQueryRef(o.str("queryRef") ?: throw PlanDecodeException("query node missing queryRef"))
                        .setParamsJson(o.str("paramsJson") ?: "{}")
                        .apply { (o["stack"] as? JsonArray)?.forEach { addStack(it.jsonPrimitive.content) } },
                )
            "reasoning" ->
                node.setReasoning(
                    ReasoningNode
                        .newBuilder()
                        .setPromptRef(o.str("promptRef") ?: "")
                        .addAllInputHandleIds(handleIds(o))
                        .setTierHint(
                            if (o.str("tierHint")?.uppercase()?.contains("STRONG") == true) {
                                ReasoningNode.TierHint.TIER_STRONG
                            } else {
                                ReasoningNode.TierHint.TIER_CHEAP
                            },
                        ).setOutputKind(ReasoningNode.OutputKind.OUTPUT_TEXT),
                )
            "dataframe" ->
                node.setDataframe(
                    DataFrameNode
                        .newBuilder()
                        .setDfdsl(o.str("dfdsl") ?: "")
                        .setSourceHandleId(
                            o.str("sourceHandleId")
                                ?: throw PlanDecodeException("dataframe node missing sourceHandleId"),
                        ),
                )
            "model" ->
                node.setModel(
                    ModelNode
                        .newBuilder()
                        .setCapabilityId(
                            o.str("capabilityId") ?: throw PlanDecodeException("model node missing capabilityId"),
                        ).addAllInputHandleIds(handleIds(o))
                        .setParamsJson(o.str("paramsJson") ?: "{}"),
                )
            "render" ->
                node.setRender(
                    RenderNode
                        .newBuilder()
                        .setKind(
                            when (o.str("renderKind")?.uppercase()) {
                                "CHART" -> RenderNode.RenderKind.RENDER_CHART
                                "NARRATIVE_FRAGMENT" -> RenderNode.RenderKind.RENDER_NARRATIVE_FRAGMENT
                                else -> RenderNode.RenderKind.RENDER_TABLE
                            },
                        ).addAllInputHandleIds(handleIds(o))
                        .setBlockRole(blockRole(o.str("blockRole")))
                        .apply { o.str("caption")?.let(::setCaption) },
                )
            else -> throw PlanDecodeException("node ${node.nodeId} has unknown/unsupported kind '${o.str("kind")}'")
        }
        return node.build()
    }

    private fun decodeEdge(o: JsonObject): DataDep =
        DataDep
            .newBuilder()
            .setFromNodeId(o.str("fromNodeId") ?: throw PlanDecodeException("edge missing fromNodeId"))
            .setToNodeId(o.str("toNodeId") ?: throw PlanDecodeException("edge missing toNodeId"))
            .setBinding(o.str("binding") ?: "")
            .build()

    private fun blockRole(s: String?): BlockRole =
        when (s?.uppercase()) {
            "EVIDENCE" -> BlockRole.EVIDENCE
            "SUMMARY" -> BlockRole.SUMMARY
            "HEADING" -> BlockRole.HEADING
            else -> BlockRole.PRIMARY
        }

    private fun parseObject(raw: String): JsonObject {
        val stripped =
            raw
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return runCatching { json.parseToJsonElement(stripped).jsonObject }
            .getOrElse { throw PlanDecodeException("planner reply is not a JSON object: ${it.message}") }
    }

    private fun handleIds(o: JsonObject): List<String> =
        (o["inputHandleIds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.dbl(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDoubleOrNull()
}
