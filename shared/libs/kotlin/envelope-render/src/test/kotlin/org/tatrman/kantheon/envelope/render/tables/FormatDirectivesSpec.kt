package org.tatrman.kantheon.envelope.render.tables

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class FormatDirectivesSpec :
    StringSpec({

        "integer columns get right-alignment but no format directive" {
            val rows =
                buildJsonArray {
                    addJsonObject { put("ROK", 2025) }
                    addJsonObject { put("ROK", 2026) }
                }
            val d = inferColumnDirectives(rows)
            d["ROK"]!!.alignment shouldBe "right"
            d["ROK"]!!.format shouldBe null // years must not be grouped/padded
        }

        "float columns get right-alignment, a rounded number intent, and the %.2f fallback" {
            val rows = buildJsonArray { addJsonObject { put("ZUSTATEK", 12500.5) } }
            val d = inferColumnDirectives(rows)
            d["ZUSTATEK"]!!.alignment shouldBe "right"
            d["ZUSTATEK"]!!.format shouldBe "%.2f"
            d["ZUSTATEK"]!!.number shouldBe ROUNDED_FLOAT
        }

        "integer columns carry no number intent (raw)" {
            val rows = buildJsonArray { addJsonObject { put("ROK", 2025) } }
            inferColumnDirectives(rows)["ROK"]!!.number shouldBe null
        }

        "string columns get no directive" {
            val rows = buildJsonArray { addJsonObject { put("NAZEV", "Odběratelé") } }
            inferColumnDirectives(rows) shouldNotContainKey "NAZEV"
        }

        "a column mixing strings and numbers is not treated as numeric" {
            val rows =
                buildJsonArray {
                    addJsonObject { put("MIXED", 1) }
                    addJsonObject { put("MIXED", "n/a") }
                }
            inferColumnDirectives(rows) shouldNotContainKey "MIXED"
        }

        "caller-supplied column specs are preserved untouched" {
            val rows = buildJsonArray { addJsonObject { put("ZUSTATEK", 1.5) } }
            val existing = mapOf("ZUSTATEK" to ColumnDirective(alignment = "left", format = "%.0f"))
            val d = inferColumnDirectives(rows, existing)
            d["ZUSTATEK"] shouldBe existing.getValue("ZUSTATEK")
        }

        "no rows returns the existing specs unchanged" {
            val existing = mapOf("X" to ColumnDirective(alignment = "center"))
            inferColumnDirectives(null, existing) shouldBe existing
        }
    })
