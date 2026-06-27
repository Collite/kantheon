package org.tatrman.kantheon.envelope.render

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.render.catalog.ChartIntentInput
import org.tatrman.kantheon.envelope.render.catalog.FormatRequest
import org.tatrman.kantheon.envelope.render.catalog.FormatToolException
import org.tatrman.kantheon.envelope.render.catalog.RenderCall
import org.tatrman.kantheon.envelope.render.catalog.TableDetailsInput
import org.tatrman.kantheon.envelope.render.fallback.FormatCatalog
import org.tatrman.kantheon.envelope.render.fallback.StructuredFormatter
import org.tatrman.kantheon.envelope.v1.FormatKind

/** A [StructuredFormatter] driven by a scripted list of per-attempt behaviours;
 *  the last entry repeats once the script is exhausted. Captures the `priorError`
 *  seen on each call so retry wiring can be asserted. */
private class ScriptedFormatter(
    private vararg val steps: suspend (FormatRequest, String?) -> RenderCall,
) : StructuredFormatter {
    val priorErrors = mutableListOf<String?>()
    private var i = 0

    override suspend fun pick(
        request: FormatRequest,
        priorError: String?,
    ): RenderCall {
        priorErrors.add(priorError)
        val step = steps.getOrElse(i) { steps.last() }
        i++
        return step(request, priorError)
    }
}

private fun fail(reason: FormatToolException.Reason): suspend (FormatRequest, String?) -> RenderCall =
    { _, _ -> throw FormatToolException(reason, "scripted ${reason.name}") }

private val ledgerRows =
    buildJsonArray {
        addJsonObject {
            put("KOD_UCTU", "311000")
            put("NAZEV", "Odběratelé")
            put("ZUSTATEK", 12500.50)
        }
        addJsonObject {
            put("KOD_UCTU", "321000")
            put("NAZEV", "Dodavatelé")
            put("ZUSTATEK", -4200.0)
            put("OBDOBI", "2026-05")
        }
    }

class FormatCatalogSpec :
    StringSpec({

        // --- gotcha: markdown re-parse trap -------------------------------------
        "markdown source is carried verbatim" {
            val md =
                """
                |## Výsledek
                |
                || účet | zůstatek |
                ||------|----------|
                || 311  | 12\,500  |
                |
                |```mermaid
                |graph TD; A-->B;
                |```
                """.trimMargin()
            val catalog = FormatCatalog(ScriptedFormatter({ _, _ -> RenderCall.Markdown(md) }))

            val r = catalog.format(FormatRequest("q", "answer"))

            r.kind shouldBe FormatKind.MARKDOWN
            r.text shouldBe md // byte-for-byte, no re-parse
            r.contentJson.shouldBeNull()
            r.format.markdown.allowMermaid
                .shouldBeTrue()
            r.fellBack.shouldBeFalse()
        }

        // --- gotcha: missing headers --------------------------------------------
        "missing headers are inferred from row keys" {
            val catalog =
                FormatCatalog(
                    ScriptedFormatter({ _, _ ->
                        RenderCall.Table(text = null, content = ledgerRows, details = TableDetailsInput())
                    }),
                )

            val r = catalog.format(FormatRequest("q", "answer"))

            r.kind shouldBe FormatKind.TABLE
            // Union of keys across rows, first-appearance order (OBDOBI only on row 2 → last).
            r.format.table.headersList
                .map { it.name } shouldContainExactly
                listOf("KOD_UCTU", "NAZEV", "ZUSTATEK", "OBDOBI")
            r.format.table.headersList
                .all { it.title == it.name }
                .shouldBeTrue()
        }

        "numeric columns get right-alignment and floats a %.2f directive" {
            val catalog =
                FormatCatalog(
                    ScriptedFormatter({ _, _ ->
                        RenderCall.Table(text = null, content = ledgerRows, details = TableDetailsInput())
                    }),
                )

            val cols =
                catalog
                    .format(FormatRequest("q", "answer"))
                    .format.table.columnsMap

            // ZUSTATEK has a fractional value → right + %.2f; KOD_UCTU is a string code → no directive.
            cols["ZUSTATEK"]!!.alignment shouldBe "right"
            cols["ZUSTATEK"]!!.format shouldBe "%.2f"
            cols.containsKey("KOD_UCTU").shouldBeFalse()
            cols.containsKey("NAZEV").shouldBeFalse()
        }

        // --- gotcha: tool_choice not honoured -----------------------------------
        "a no-tool-call attempt is retried with the prior error" {
            val formatter =
                ScriptedFormatter(
                    fail(FormatToolException.Reason.NO_TOOL_CALL),
                    { _, _ -> RenderCall.Plaintext("recovered") },
                )
            val catalog = FormatCatalog(formatter)

            val r = catalog.format(FormatRequest("q", "answer"))

            r.kind shouldBe FormatKind.PLAINTEXT
            r.text shouldBe "recovered"
            r.fellBack.shouldBeFalse()
            // First call sees no prior error; the retry is fed the NO_TOOL_CALL reason.
            formatter.priorErrors[0].shouldBeNull()
            formatter.priorErrors[1]!!.contains("NO_TOOL_CALL").shouldBeTrue()
        }

        // --- gotcha: retry exhaustion -------------------------------------------
        "retry exhaustion with structured rows falls back to a table" {
            val formatter = ScriptedFormatter(fail(FormatToolException.Reason.SCHEMA_INVALID))
            val catalog = FormatCatalog(formatter, maxRetries = 2)

            val r =
                catalog.format(
                    FormatRequest("q", "answer", rows = ledgerRows, desiredKind = FormatKind.CHART),
                )

            r.fellBack.shouldBeTrue()
            r.kind shouldBe FormatKind.TABLE
            r.fallbackFrom shouldBe FormatKind.CHART
            r.format.table.alternateColors shouldBe "Rows"
            r.format.table.headersList.map { it.name }.shouldContainExactly(
                listOf("KOD_UCTU", "NAZEV", "ZUSTATEK", "OBDOBI"),
            )
            // maxRetries + 1 = 3 attempts before the fallback.
            formatter.priorErrors.size shouldBe 3
        }

        "retry exhaustion with no structure falls back to plaintext" {
            val catalog =
                FormatCatalog(ScriptedFormatter(fail(FormatToolException.Reason.LLM_ERROR)), maxRetries = 1)

            val r = catalog.format(FormatRequest("q", "the answer text", rows = null))

            r.fellBack.shouldBeTrue()
            r.kind shouldBe FormatKind.PLAINTEXT
            r.text shouldBe "the answer text"
        }

        // --- gotcha: chart-on-text-heavy-data -----------------------------------
        "a chart request on text-heavy data falls back to a table" {
            val textHeavy =
                buildJsonArray {
                    addJsonObject {
                        put("KOD", "A1")
                        put("NAZEV", "Alfa")
                    }
                    addJsonObject {
                        put("KOD", "B2")
                        put("NAZEV", "Beta")
                    }
                }
            // The model cannot satisfy a chart on id/name-only rows → fails every attempt.
            val catalog = FormatCatalog(ScriptedFormatter(fail(FormatToolException.Reason.SCHEMA_INVALID)))

            val r =
                catalog.format(
                    FormatRequest("graf prosím", "answer", rows = textHeavy, desiredKind = FormatKind.CHART),
                )

            r.kind shouldBe FormatKind.TABLE // a readable table, not a str(dict) dump
            r.fellBack.shouldBeTrue()
            r.fallbackFrom shouldBe FormatKind.CHART
        }

        // --- happy path: chart intent carried -----------------------------------
        "a chart tool call carries the intent and content" {
            val series =
                buildJsonArray {
                    addJsonObject {
                        put("OBDOBI", "2026-04")
                        put("TRZBY", 100.0)
                    }
                    addJsonObject {
                        put("OBDOBI", "2026-05")
                        put("TRZBY", 130.0)
                    }
                }
            val catalog =
                FormatCatalog(
                    ScriptedFormatter({ _, _ ->
                        RenderCall.Chart(
                            text = "Tržby rostou",
                            content = series,
                            intent = ChartIntentInput(kind = "line", x = "OBDOBI", y = listOf("TRZBY")),
                        )
                    }),
                )

            val r = catalog.format(FormatRequest("vývoj tržeb", "answer"))

            r.kind shouldBe FormatKind.CHART
            r.text shouldBe "Tržby rostou"
            r.format.chart.intent.kind shouldBe "line"
            r.format.chart.intent.x shouldBe "OBDOBI"
            r.format.chart.intent.yList
                .shouldContainExactly(listOf("TRZBY"))
            r.contentJson shouldBe series.toString()
        }
    })
