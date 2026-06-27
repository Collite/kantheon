package org.tatrman.kantheon.iris.dispatch.golemv2

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.envelope.v1.FormatEnvelope

/**
 * Normalise a new-golem /v2 envelope JSON into an `envelope/v1` `FormatEnvelope`
 * (the KT mirror of `envelope-ts/src/normalize.ts`, contracts §1.1/§5):
 *  - enum casing: v2 lowercase → proto NAME (UPPERCASE);
 *  - opaque-JSON fields (`content`, chart `vega_lite_spec`/`rows`, filter
 *    `value`, `current_view.args`, chip `prefilled_args`) → Rule-7 `*_json`;
 *  - v2 chip → the `Chip.prompt` oneof arm.
 * The normalised snake_case JSON is then parsed via protobuf `JsonFormat`
 * (which tolerantly reads the original field names). `agent_id` is BFF-enriched.
 */
object V2EnvelopeNormalizer {
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    fun toEnvelope(
        raw: JsonObject,
        agentId: String = "golem-v2",
    ): FormatEnvelope {
        val builder = FormatEnvelope.newBuilder()
        parser.merge(normalizeJson(raw).toString(), builder)
        builder.agentId = agentId
        return builder.build()
    }

    private fun enumName(e: JsonElement?): JsonElement? =
        (e as? JsonPrimitive)?.contentOrNullSafe()?.let { JsonPrimitive(it.uppercase()) } ?: e

    /** Rule-7: encode any value as a JSON string (so it round-trips through parse). */
    private fun toJsonString(e: JsonElement?): JsonElement? =
        if (e == null || e is kotlinx.serialization.json.JsonNull) null else JsonPrimitive(e.toString())

    fun normalizeJson(raw: JsonObject): JsonObject =
        buildJsonObject {
            raw.forEach { (k, v) ->
                when (k) {
                    "plan_source" -> enumName(v)?.let { put(k, it) }
                    "content" -> toJsonString(v)?.let { put("content_json", it) }
                    "format" -> put("format", normalizeFormat(v))
                    "chips" -> put("chips", normalizeChips(v))
                    "current_view" -> put("current_view", normalizeCurrentView(v))
                    else -> put(k, v)
                }
            }
        }

    private fun normalizeFormat(format: JsonElement): JsonElement {
        val obj = format as? JsonObject ?: return format
        return buildJsonObject {
            obj["kind"]?.let { put("kind", enumName(it)!!) }
            (obj["table"] as? JsonObject)?.let { put("table", normalizeTable(it)) }
            (obj["chart"] as? JsonObject)?.let { put("chart", normalizeChart(it)) }
            (obj["markdown"] as? JsonObject)?.let { put("markdown", it) }
            // older v2 nests per-kind under format.details
            (obj["details"] as? JsonObject)?.let { d ->
                when ((obj["kind"] as? JsonPrimitive)?.contentOrNullSafe()) {
                    "table" -> put("table", normalizeTable(d))
                    "chart" -> put("chart", normalizeChart(d))
                    "markdown" -> put("markdown", d)
                    else -> Unit
                }
            }
        }
    }

    private fun normalizeTable(t: JsonObject): JsonObject =
        buildJsonObject {
            t.forEach { (k, v) ->
                when (k) {
                    "alternateColors" -> put("alternate_colors", v)
                    "filters" -> put("filters", normalizeFilters(v))
                    else -> put(k, v)
                }
            }
        }

    private fun normalizeFilters(filters: JsonElement): JsonArray =
        buildJsonArray {
            (filters as? JsonArray)?.forEach { f ->
                val o = f as? JsonObject
                if (o == null) {
                    add(f)
                    return@forEach
                }
                add(
                    buildJsonObject {
                        o.forEach { (k, v) ->
                            if (k ==
                                "value"
                            ) {
                                toJsonString(v)?.let { put("value_json", it) }
                            } else {
                                put(k, v)
                            }
                        }
                    },
                )
            }
        }

    private fun normalizeChart(c: JsonObject): JsonObject =
        buildJsonObject {
            c.forEach { (k, v) ->
                when (k) {
                    "vega_lite_spec" -> toJsonString(v)?.let { put("vega_lite_spec_json", it) }
                    "rows" -> toJsonString(v)?.let { put("rows_json", it) }
                    else -> put(k, v)
                }
            }
        }

    private fun normalizeChips(chips: JsonElement): JsonArray =
        buildJsonArray {
            (chips as? JsonArray)?.forEach { chip ->
                val o = chip as? JsonObject ?: return@forEach
                add(
                    buildJsonObject {
                        put(
                            "prompt",
                            buildJsonObject {
                                o["display"]?.let { put("display", it) }
                                o["prompt"]?.let { put("prompt", it) }
                                o["source"]?.let { put("source", it) }
                                o["pattern_id"]?.let { put("pattern_id", it) }
                                o["prefilled_args"]?.let { pa ->
                                    toJsonString(pa)?.let { put("prefilled_args_json", it) }
                                }
                            },
                        )
                    },
                )
            }
        }

    private fun normalizeCurrentView(cv: JsonElement): JsonElement {
        val obj = cv as? JsonObject ?: return cv
        return buildJsonObject {
            obj.forEach { (k, v) -> if (k == "args") toJsonString(v)?.let { put("args_json", it) } else put(k, v) }
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String = content
}
