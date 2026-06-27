package org.tatrman.kantheon.golem.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode
import org.tatrman.kantheon.golem.v1.ReasoningNode
import org.tatrman.kantheon.golem.v1.RenderNode

/** The composer's LLM reply could not be decoded into a [MiniPlan]. */
class PlanDecodeException(
    message: String,
) : RuntimeException(message)

/**
 * Decodes the plan-composer LLM reply (a discriminated MiniPlan JSON, contract in
 * the `intent` prompt) into a golem/v1 [MiniPlan]. StructureFixingParser-free —
 * the same fence-strip + manual-read approach as envelope-render's `RenderCallCodec`
 * and themis's `JointInferenceNode`. Unknown enum values / malformed shapes raise
 * [PlanDecodeException]; the caller decides the fallback (a clarification).
 */
object MiniPlanCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): MiniPlan {
        val obj = parseObject(raw)
        val source = enumOrThrow("source", str(obj, "source")) { PlanSource.valueOf(it) }
        val b =
            MiniPlan
                .newBuilder()
                .setSource(source)
                .setConfidence(obj["confidence"]?.jsonPrimitive?.double ?: 0.0)
                .setRationale(strOrEmpty(obj, "rationale"))
        strOrNull(obj, "losing_plan_summary")?.let { b.losingPlanSummary = it }
        obj["nodes"]?.jsonArray?.forEach { b.addNodes(decodeNode(it.jsonObject)) }
        return b.build()
    }

    private fun decodeNode(node: JsonObject): MiniPlanNode {
        val nb = MiniPlanNode.newBuilder().setNodeId(strOrEmpty(node, "node_id"))
        when {
            node.containsKey("query") -> nb.query = decodeQuery(node["query"]!!.jsonObject)
            node.containsKey("render") -> nb.render = decodeRender(node["render"]!!.jsonObject)
            node.containsKey("reasoning") -> nb.reasoning = decodeReasoning(node["reasoning"]!!.jsonObject)
            else -> throw PlanDecodeException("node '${strOrEmpty(node, "node_id")}' has no query/render/reasoning")
        }
        return nb.build()
    }

    private fun decodeQuery(q: JsonObject): QueryNode {
        val b =
            QueryNode
                .newBuilder()
                .setSource(strOrEmpty(q, "source"))
                .setSourceLanguage(strOrEmpty(q, "source_language"))
                .setParamsJson(paramsJson(q["params_json"]))
                .setCompileFirst(q["compile_first"]?.jsonPrimitive?.booleanOrNull ?: false)
        strOrNull(q, "pattern_id")?.let { b.patternId = it }
        return b.build()
    }

    private fun decodeRender(r: JsonObject): RenderNode {
        val b =
            RenderNode
                .newBuilder()
                .setKindHint(
                    enumOrThrow(
                        "kind_hint",
                        strOrEmpty(r, "kind_hint").ifBlank {
                            "FORMAT_KIND_UNSPECIFIED"
                        },
                    ) { FormatKind.valueOf(it) },
                )
        r["input_node_ids"]?.jsonArray?.forEach { b.addInputNodeIds(it.jsonPrimitive.content) }
        strOrNull(r, "caption")?.let { b.caption = it }
        return b.build()
    }

    private fun decodeReasoning(r: JsonObject): ReasoningNode {
        val b =
            ReasoningNode
                .newBuilder()
                .setPromptRef(strOrEmpty(r, "prompt_ref"))
                .setOutputKind(strOrEmpty(r, "output_kind"))
        r["input_node_ids"]?.jsonArray?.forEach { b.addInputNodeIds(it.jsonPrimitive.content) }
        return b.build()
    }

    /** `params_json` may arrive as a JSON string OR an inline object — normalise to a string. */
    private fun paramsJson(el: kotlinx.serialization.json.JsonElement?): String =
        when {
            el == null -> "{}"
            el is JsonObject -> el.toString()
            el is JsonPrimitive && el.isString -> el.content
            else -> el.toString()
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
            .getOrElse { throw PlanDecodeException("composer reply is not a JSON object: ${it.message}") }
    }

    private fun str(
        obj: JsonObject,
        key: String,
    ): String = strOrNull(obj, key) ?: throw PlanDecodeException("missing required field '$key'")

    private fun strOrEmpty(
        obj: JsonObject,
        key: String,
    ): String = strOrNull(obj, key) ?: ""

    private fun strOrNull(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private inline fun <T> enumOrThrow(
        field: String,
        value: String,
        convert: (String) -> T,
    ): T =
        runCatching {
            convert(value.trim())
        }.getOrElse { throw PlanDecodeException("$field has an unknown value '$value'") }
}
