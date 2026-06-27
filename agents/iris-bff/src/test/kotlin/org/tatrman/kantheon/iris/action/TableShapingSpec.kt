package org.tatrman.kantheon.iris.action

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private fun row(
    m: String,
    r: Int,
): JsonObject =
    buildJsonObject {
        put("m", JsonPrimitive(m))
        put("r", JsonPrimitive(r))
    }

private val rows =
    listOf(row("2026-01", 120), row("2026-02", 98), row("2026-03", 140), row("2026-04", 110))

class TableShapingSpec :
    StringSpec({

        "sort asc by numeric column orders numerically (not lexically)" {
            val out = TableShaping.shape(rows, emptyList(), TableShaping.Sort("r", "asc"), null, null)
            out.map { (it["r"] as JsonPrimitive).content } shouldBe listOf("98", "110", "120", "140")
        }

        "sort desc reverses" {
            val out = TableShaping.shape(rows, emptyList(), TableShaping.Sort("r", "desc"), null, null)
            out.first()["r"] shouldBe JsonPrimitive(140)
        }

        "filter gt keeps numeric rows above the threshold" {
            val out =
                TableShaping.shape(
                    rows,
                    listOf(TableShaping.Filter("r", "gt", JsonPrimitive(110))),
                    null,
                    null,
                    null,
                )
            out.map { (it["m"] as JsonPrimitive).content } shouldBe listOf("2026-01", "2026-03")
        }

        "filter contains matches substrings case-insensitively" {
            val out =
                TableShaping.shape(
                    rows,
                    listOf(TableShaping.Filter("m", "contains", JsonPrimitive("2026-0"))),
                    null,
                    null,
                    null,
                )
            out.size shouldBe 4
        }

        "filter in matches the set" {
            val out =
                TableShaping.shape(
                    rows,
                    listOf(
                        TableShaping.Filter(
                            "m",
                            "in",
                            JsonArray(listOf(JsonPrimitive("2026-01"), JsonPrimitive("2026-04"))),
                        ),
                    ),
                    null,
                    null,
                    null,
                )
            out.map { (it["m"] as JsonPrimitive).content } shouldBe listOf("2026-01", "2026-04")
        }

        "paginate slices by page/pageSize (1-based)" {
            val out = TableShaping.shape(rows, emptyList(), null, page = 2, pageSize = 2)
            out.map { (it["m"] as JsonPrimitive).content } shouldBe listOf("2026-03", "2026-04")
        }

        "a page beyond the data is empty" {
            TableShaping.shape(rows, emptyList(), null, page = 5, pageSize = 2) shouldBe emptyList()
        }

        "an enormous page does not overflow (Long offset math) — empty, no exception" {
            // (page-1)*pageSize would overflow Int and throw on subList; Long math clamps.
            TableShaping.shape(rows, emptyList(), null, page = 2_000_000_000, pageSize = 2_000_000_000) shouldBe
                emptyList()
        }

        "an unknown filter operator matches nothing (and is rejected at parse)" {
            val out =
                TableShaping.shape(
                    rows,
                    listOf(TableShaping.Filter("r", "bogus", JsonPrimitive(110))),
                    null,
                    null,
                    null,
                )
            out shouldBe emptyList()
            ("bogus" in TableShaping.VALID_OPERATORS) shouldBe false
        }

        "filter then sort then paginate compose in order" {
            val out =
                TableShaping.shape(
                    rows,
                    listOf(TableShaping.Filter("r", "gte", JsonPrimitive(110))),
                    TableShaping.Sort("r", "desc"),
                    page = 1,
                    pageSize = 2,
                )
            out.map { (it["r"] as JsonPrimitive).content } shouldBe listOf("140", "120")
        }
    })
