package org.tatrman.kantheon.envelope.render.charts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.v1.ChartIntent

/**
 * Compiles an envelope/v1 [ChartIntent] + row content into a Vega-Lite v5 spec.
 *
 * A behaviour-preserving port of new-golem v2's `vega_lite_compiler.py`
 * (`compile_chart`), cross-checked against agents-fe `compileVegaLite.ts`. The
 * non-empty paths are byte-identical across all three.
 *
 * **Known parity divergence (empty content).** The Python special-cases empty
 * `content` with `_empty_spec` (a single-series x-nominal / y[0]-quantitative
 * shape regardless of kind); the TS does not. This port follows the **Python**
 * (the source it replaces, with the parity test oracle). A chart with no rows is
 * degenerate, so the divergence is unreachable on real turns; documented for the
 * record.
 */
object VegaLiteCompiler {
    const val SCHEMA = "https://vega.github.io/schema/vega-lite/v5.json"
    const val PRIMARY_COLOR = "#3B82F6"

    fun compile(
        intent: ChartIntent,
        content: JsonArray,
    ): JsonObject {
        // Guards (Stage 2.1 review carry-in): a chart needs at least one y series — `emptySpec`
        // and the single-series builders index `yList.first()` / `visibleY[0]`, which would throw
        // an opaque IndexOutOfBounds on an empty `y`. Fail loud with the offending intent instead.
        require(intent.yList.isNotEmpty()) {
            "ChartIntent.y must have at least one series (kind='${intent.kind}')"
        }
        val visibleY =
            intent.yList
                .filter { it !in intent.hideSeriesList }
                .ifEmpty { intent.yList.take(1) }

        val spec =
            if (content.isEmpty()) {
                emptySpec(intent)
            } else {
                buildJsonObject {
                    put("\$schema", SCHEMA)
                    put("data", buildJsonObject { put("values", content) })
                    when (intent.kind) {
                        "line" -> lineSpec(intent, visibleY, this)
                        "bar" -> barSpec(intent, visibleY, this)
                        "pie" -> pieSpec(intent, this)
                        "scatter" -> scatterSpec(intent, visibleY, this)
                        "area" -> areaSpec(intent, visibleY, this)
                        // `kind` is a free string on the wire — an out-of-domain value would
                        // otherwise silently render a $schema+data-only (blank) spec.
                        else -> error("unsupported ChartIntent.kind '${intent.kind}'")
                    }
                }
            }

        if (!intent.hasTitle()) return spec
        return JsonObject(spec + ("title" to kotlinx.serialization.json.JsonPrimitive(intent.title)))
    }

    private fun emptySpec(intent: ChartIntent): JsonObject =
        buildJsonObject {
            put("\$schema", SCHEMA)
            put("data", buildJsonObject { put("values", JsonArray(emptyList())) })
            put(
                "mark",
                buildJsonObject {
                    put("type", if (intent.kind == "pie") "arc" else intent.kind)
                    put("color", PRIMARY_COLOR)
                },
            )
            put(
                "encoding",
                buildJsonObject {
                    put("x", field(intent.x, "nominal"))
                    put("y", field(intent.yList.first(), "quantitative"))
                },
            )
        }

    // --- per-kind builders (mutate the open JsonObjectBuilder, mirroring Python) ---

    private fun lineSpec(
        intent: ChartIntent,
        visibleY: List<String>,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) {
        if (visibleY.size == 1) {
            b.put("mark", mark("line", withColor = true))
            b.put(
                "encoding",
                buildJsonObject {
                    put("x", xNominalAxis(intent.x))
                    put(
                        "y",
                        buildJsonObject {
                            put("field", visibleY[0])
                            put("type", "quantitative")
                            put("title", visibleY[0])
                        },
                    )
                },
            )
        } else {
            b.put("mark", mark("line", withColor = false))
            b.put("transform", foldTransform(visibleY))
            b.put("encoding", multiSeriesEncoding(intent, xNominalAxis(intent.x)))
        }
    }

    private fun barSpec(
        intent: ChartIntent,
        visibleY: List<String>,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) = stackedKind("bar", intent, visibleY, b)

    private fun areaSpec(
        intent: ChartIntent,
        visibleY: List<String>,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) = stackedKind("area", intent, visibleY, b)

    /** bar + area share the identical structure (x nominal + stack rule). */
    private fun stackedKind(
        kind: String,
        intent: ChartIntent,
        visibleY: List<String>,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) {
        if (visibleY.size == 1) {
            b.put("mark", mark(kind, withColor = true))
            b.put(
                "encoding",
                buildJsonObject {
                    put("x", xNominalAxis(intent.x))
                    put("y", yQuantStacked(visibleY[0], intent.stacked))
                },
            )
        } else {
            b.put("mark", mark(kind, withColor = false))
            b.put("transform", foldTransform(visibleY))
            b.put(
                "encoding",
                buildJsonObject {
                    put("x", xNominalAxis(intent.x))
                    put("y", yValueStacked(intent.stacked))
                    put("color", seriesColor(intent.showLegend))
                },
            )
        }
    }

    private fun pieSpec(
        intent: ChartIntent,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) {
        b.put("mark", buildJsonObject { put("type", "arc") })
        b.put(
            "encoding",
            buildJsonObject {
                put("theta", field(intent.yList.first(), "quantitative"))
                put(
                    "color",
                    buildJsonObject {
                        put("field", intent.x)
                        put("type", "nominal")
                        put("legend", legend(intent.showLegend))
                    },
                )
            },
        )
    }

    private fun scatterSpec(
        intent: ChartIntent,
        visibleY: List<String>,
        b: kotlinx.serialization.json.JsonObjectBuilder,
    ) {
        if (visibleY.size == 1) {
            b.put("mark", mark("point", withColor = true))
            b.put(
                "encoding",
                buildJsonObject {
                    put("x", field(intent.x, "quantitative")) // scatter x is quantitative, not nominal
                    put("y", field(visibleY[0], "quantitative"))
                },
            )
        } else {
            b.put("mark", mark("point", withColor = false))
            b.put("transform", foldTransform(visibleY))
            b.put(
                "encoding",
                buildJsonObject {
                    put("x", field(intent.x, "quantitative"))
                    put("y", field("value", "quantitative"))
                    put("color", seriesColor(intent.showLegend))
                },
            )
        }
    }

    // --- shared fragments ---------------------------------------------------

    private fun mark(
        type: String,
        withColor: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            if (withColor) put("color", PRIMARY_COLOR)
        }

    private fun field(
        name: String,
        type: String,
    ): JsonObject =
        buildJsonObject {
            put("field", name)
            put("type", type)
        }

    private fun xNominalAxis(x: String): JsonObject =
        buildJsonObject {
            put("field", x)
            put("type", "nominal")
            put("axis", buildJsonObject { put("labelAngle", 0) })
        }

    private fun yQuantStacked(
        yField: String,
        stacked: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("field", yField)
            put("type", "quantitative")
            put("stack", if (stacked) kotlinx.serialization.json.JsonPrimitive("zero") else JsonNull)
        }

    private fun yValueStacked(stacked: Boolean): JsonObject =
        buildJsonObject {
            put("field", "value")
            put("type", "quantitative")
            put("stack", if (stacked) kotlinx.serialization.json.JsonPrimitive("zero") else JsonNull)
        }

    private fun foldTransform(visibleY: List<String>): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("fold", buildJsonArray { visibleY.forEach { add(it) } })
                    put(
                        "as",
                        buildJsonArray {
                            add("series")
                            add("value")
                        },
                    )
                },
            )
        }

    private fun multiSeriesEncoding(
        intent: ChartIntent,
        x: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("x", x)
            put("y", field("value", "quantitative"))
            put("color", seriesColor(intent.showLegend))
        }

    private fun seriesColor(showLegend: Boolean): JsonObject =
        buildJsonObject {
            put("field", "series")
            put("type", "nominal")
            put("legend", legend(showLegend))
        }

    /** `{"title": null}` when shown, else `null` (Vega reads absence as default). */
    private fun legend(show: Boolean): kotlinx.serialization.json.JsonElement =
        if (show) buildJsonObject { put("title", JsonNull) } else JsonNull
}
