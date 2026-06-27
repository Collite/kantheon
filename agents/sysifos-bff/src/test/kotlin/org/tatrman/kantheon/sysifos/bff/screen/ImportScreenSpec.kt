package org.tatrman.kantheon.sysifos.bff.screen

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ImportScreenSpec :
    StringSpec({

        "folds the loader run + preview rows + summary into one payload" {
            val run = """{"loaderRunId":"r-1","status":"LR_PREVIEW_READY","rowCountTotal":3}"""
            val preview =
                """{"loaderRunId":"r-1","rows":[
                   {"sourceRowIndex":0,"decision":"PV_NEW"},
                   {"sourceRowIndex":1,"decision":"PV_DUPLICATE","note":"duplicate of t-9"}
                ],"summary":{"newCount":1,"duplicateCount":1,"errorCount":0}}"""

            val out = Json.parseToJsonElement(assembleImportScreen(run, preview)).jsonObject
            out["loaderRun"]!!.jsonObject["loaderRunId"]!!.jsonPrimitive.content shouldBe "r-1"
            out["rows"]!!.jsonArray shouldHaveSize 2
            out["summary"]!!.jsonObject["duplicateCount"]!!.jsonPrimitive.content shouldBe "1"
        }

        "tolerates an empty preview body" {
            val out = Json.parseToJsonElement(assembleImportScreen("""{"loaderRunId":"r-2"}""", "")).jsonObject
            out["rows"]!!.jsonArray shouldHaveSize 0
        }
    })
