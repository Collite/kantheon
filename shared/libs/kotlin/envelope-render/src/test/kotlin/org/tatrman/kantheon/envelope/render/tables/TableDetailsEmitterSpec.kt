package org.tatrman.kantheon.envelope.render.tables

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

class TableDetailsEmitterSpec :
    StringSpec({

        "builds headers + typed column specs (numeric→right, float→number+%.2f, integers raw)" {
            val rows =
                buildJsonArray {
                    addJsonObject {
                        put("KOD_STR", "DF01")
                        put("ROK", 2025)
                        put("ZUSTATEK", 12500.5)
                    }
                }
            val td = typedTableDetails(rows)

            td.headersList.map { it.name } shouldBe listOf("KOD_STR", "ROK", "ZUSTATEK")

            // String column → no directive.
            td.columnsMap.containsKey("KOD_STR") shouldBe false
            // Integer column → right-aligned, no number intent (codes/IDs/years stay raw).
            td.columnsMap["ROK"]!!.alignment shouldBe "right"
            td.columnsMap["ROK"]!!.hasNumber() shouldBe false
            // Float column → right-aligned, rounded number intent + the %.2f fallback.
            td.columnsMap["ZUSTATEK"]!!.alignment shouldBe "right"
            td.columnsMap["ZUSTATEK"]!!.format shouldBe "%.2f"
            td.columnsMap["ZUSTATEK"]!!.number.minimumFractionDigits shouldBe 2
            td.columnsMap["ZUSTATEK"]!!.number.maximumFractionDigits shouldBe 2
            td.columnsMap["ZUSTATEK"]!!.number.useGrouping shouldBe true
        }
    })
