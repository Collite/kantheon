package org.tatrman.kantheon.envelope.render.tables

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HeaderInferenceSpec :
    StringSpec({

        "unions keys across rows in first-appearance order" {
            val rows =
                buildJsonArray {
                    addJsonObject {
                        put("a", 1)
                        put("b", 2)
                    }
                    addJsonObject {
                        put("b", 3)
                        put("c", 4) // new key contributed by a later row → appended last
                    }
                }
            inferTableHeaders(rows).map { it.name } shouldContainExactly listOf("a", "b", "c")
        }

        "a single object yields its own keys" {
            val obj =
                buildJsonObject {
                    put("x", 1)
                    put("y", 2)
                }
            inferTableHeaders(obj).map { it.name } shouldContainExactly listOf("x", "y")
        }

        "title equals name verbatim — no snake_case transform" {
            val rows = buildJsonArray { addJsonObject { put("KOD_UCTU", "311") } }
            inferTableHeaders(rows).single().let {
                it.name shouldBe "KOD_UCTU"
                it.title shouldBe "KOD_UCTU"
            }
        }

        "non-object rows are skipped; null / non-structured yields no headers" {
            val rows =
                buildJsonArray {
                    add(JsonPrimitive("scalar"))
                    addJsonObject { put("k", 1) }
                    add(JsonNull)
                }
            inferTableHeaders(rows).map { it.name } shouldContainExactly listOf("k")
            inferTableHeaders(null).shouldBeEmpty()
            inferTableHeaders(JsonPrimitive(7)).shouldBeEmpty()
        }

        "inference is idempotent and order-stable" {
            checkAll(Arb.list(Arb.stringPattern("[a-z]{1,4}"), 0..8)) { keys ->
                val rows = buildJsonArray { addJsonObject { keys.forEach { put(it, 1) } } }
                val once = inferTableHeaders(rows)
                val twice = inferTableHeaders(rows)
                once shouldBe twice
                // names are exactly the distinct keys, in first-appearance order.
                once.map { it.name } shouldContainExactly keys.distinct()
            }
        }
    })
