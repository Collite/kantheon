package org.tatrman.kantheon.envelope.render.charts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.v1.ChartIntent

private fun intent(
    kind: String,
    x: String,
    y: List<String>,
    title: String? = null,
    stacked: Boolean = false,
    showLegend: Boolean = true,
    hideSeries: List<String> = emptyList(),
): ChartIntent {
    val b =
        ChartIntent
            .newBuilder()
            .setKind(kind)
            .setX(x)
            .addAllY(y)
            .setStacked(stacked)
            .setShowLegend(showLegend)
            .addAllHideSeries(hideSeries)
    title?.let { b.title = it }
    return b.build()
}

private val janFeb =
    buildJsonArray {
        addJsonObject {
            put("month", "Jan")
            put("revenue", 1200)
            put("cost", 800)
        }
        addJsonObject {
            put("month", "Feb")
            put("revenue", 1800)
            put("cost", 900)
        }
    }

private fun JsonObject.enc(channel: String): JsonObject = this["encoding"]!!.jsonObject[channel]!!.jsonObject

class VegaLiteCompilerSpec :
    StringSpec({

        // --- line ---------------------------------------------------------------
        "line single-series: line mark, nominal x, quantitative y, data echoed" {
            val spec = VegaLiteCompiler.compile(intent("line", "month", listOf("revenue")), janFeb)
            spec["\$schema"]!!.jsonPrimitive.content shouldBe VegaLiteCompiler.SCHEMA
            spec["mark"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "line"
            spec.enc("x")["field"]!!.jsonPrimitive.content shouldBe "month"
            spec.enc("y")["field"]!!.jsonPrimitive.content shouldBe "revenue"
            spec["data"]!!.jsonObject["values"]!!.jsonArray shouldBe janFeb
        }

        "line with a title carries it through" {
            val spec =
                VegaLiteCompiler.compile(
                    intent("line", "month", listOf("revenue"), title = "Revenue"),
                    JsonArray(emptyList()),
                )
            spec["title"]!!.jsonPrimitive.content shouldBe "Revenue"
        }

        "line multi-series folds into series/value with a color channel; no layer" {
            val spec = VegaLiteCompiler.compile(intent("line", "month", listOf("revenue", "cost")), janFeb)
            spec.containsKey("layer") shouldBe false
            spec.containsKey("transform") shouldBe true
            spec.enc("color")["field"]!!.jsonPrimitive.content shouldBe "series"
        }

        "line show_legend=false nulls the legend on the color channel (multi-series only)" {
            val spec =
                VegaLiteCompiler.compile(
                    intent("line", "month", listOf("revenue", "cost"), showLegend = false),
                    janFeb,
                )
            spec.enc("color")["legend"] shouldBe JsonNull
        }

        // --- bar ----------------------------------------------------------------
        "bar stacked sets y.stack=zero (not on the mark)" {
            val spec =
                VegaLiteCompiler.compile(
                    intent("bar", "month", listOf("revenue", "cost"), stacked = true),
                    janFeb,
                )
            spec["mark"]!!.jsonObject.containsKey("stack") shouldBe false
            spec.enc("y")["stack"]!!.jsonPrimitive.content shouldBe "zero"
        }

        "bar not stacked leaves y.stack=null" {
            val spec = VegaLiteCompiler.compile(intent("bar", "month", listOf("revenue", "cost")), janFeb)
            spec.enc("y")["stack"] shouldBe JsonNull
        }

        "bar hide_series drops the hidden series and reverts to single-series mode" {
            val spec =
                VegaLiteCompiler.compile(
                    intent("bar", "month", listOf("revenue", "cost"), hideSeries = listOf("cost")),
                    janFeb,
                )
            spec.enc("y")["field"]!!.jsonPrimitive.content shouldBe "revenue"
            spec.containsKey("transform") shouldBe false
        }

        // --- pie ----------------------------------------------------------------
        "pie uses an arc mark with theta=y[0] and color=x" {
            val spec = VegaLiteCompiler.compile(intent("pie", "category", listOf("amount")), janFeb)
            spec["mark"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "arc"
            spec.enc("theta")["field"]!!.jsonPrimitive.content shouldBe "amount"
            spec.enc("color")["field"]!!.jsonPrimitive.content shouldBe "category"
        }

        // --- scatter ------------------------------------------------------------
        "scatter uses a point mark with QUANTITATIVE x" {
            val spec = VegaLiteCompiler.compile(intent("scatter", "size", listOf("price")), janFeb)
            spec["mark"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "point"
            spec.enc("x")["type"]!!.jsonPrimitive.content shouldBe "quantitative"
        }

        // --- area ---------------------------------------------------------------
        "area mirrors bar (nominal x, stack rule)" {
            val spec =
                VegaLiteCompiler.compile(
                    intent("area", "month", listOf("revenue", "cost"), stacked = true),
                    janFeb,
                )
            spec["mark"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "area"
            spec.enc("x")["type"]!!.jsonPrimitive.content shouldBe "nominal"
            spec.enc("y")["stack"]!!.jsonPrimitive.content shouldBe "zero"
        }

        // --- empty content (Python _empty_spec parity) --------------------------
        "empty content yields the single-series empty spec with data.values = []" {
            val spec = VegaLiteCompiler.compile(intent("line", "month", listOf("revenue")), JsonArray(emptyList()))
            spec["data"]!!.jsonObject["values"]!!.jsonArray shouldBe JsonArray(emptyList())
            spec["mark"]!!.jsonObject["color"]!!.jsonPrimitive.content shouldBe VegaLiteCompiler.PRIMARY_COLOR
            spec.enc("x")["type"]!!.jsonPrimitive.content shouldBe "nominal"
            spec.enc("y")["field"]!!.jsonPrimitive.content shouldBe "revenue"
        }

        // ---- Stage 2.1-review guards (fail loud, never render blank) --------------------

        "an out-of-domain kind fails loud rather than rendering a blank spec" {
            val rows =
                buildJsonArray {
                    addJsonObject {
                        put("month", "Jan")
                        put("revenue", 1)
                    }
                }
            shouldThrow<IllegalStateException> {
                VegaLiteCompiler.compile(intent("treemap", "month", listOf("revenue")), rows)
            }
        }

        "an empty y series fails loud rather than throwing IndexOutOfBounds" {
            shouldThrow<IllegalArgumentException> {
                VegaLiteCompiler.compile(intent("line", "month", emptyList()), JsonArray(emptyList()))
            }
        }
    })
