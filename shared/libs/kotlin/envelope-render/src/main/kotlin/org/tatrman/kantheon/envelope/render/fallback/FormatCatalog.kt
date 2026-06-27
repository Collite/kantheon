package org.tatrman.kantheon.envelope.render.fallback

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.envelope.render.catalog.ChartIntentInput
import org.tatrman.kantheon.envelope.render.catalog.FormatRequest
import org.tatrman.kantheon.envelope.render.catalog.FormatResult
import org.tatrman.kantheon.envelope.render.catalog.FormatToolException
import org.tatrman.kantheon.envelope.render.catalog.RenderCall
import org.tatrman.kantheon.envelope.render.catalog.TableDetailsInput
import org.tatrman.kantheon.envelope.render.charts.VegaLiteCompiler
import org.tatrman.kantheon.envelope.render.tables.ColumnDirective
import org.tatrman.kantheon.envelope.render.tables.inferColumnDirectives
import org.tatrman.kantheon.envelope.render.tables.inferTableHeaders
import org.tatrman.kantheon.envelope.v1.ChartIntent
import org.tatrman.kantheon.envelope.v1.ChartIntentDetails
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.MarkdownDetails
import org.tatrman.kantheon.envelope.v1.TableColumnSpec
import org.tatrman.kantheon.envelope.v1.TableDetails
import org.tatrman.kantheon.envelope.v1.TableHeader

private val log = KotlinLogging.logger {}

/**
 * The format catalog orchestrator (port of new-golem v2's `_build_format_envelope`,
 * ai-platform golem `nodes.py:1095` @ git 5281954d^).
 *
 * Drives a [StructuredFormatter] up to `maxRetries + 1` times, feeding each
 * failure into the next attempt's repair prompt. On success it realises the
 * chosen [RenderCall] into an envelope/v1 [FormatSpec] — inferring table headers
 * and column directives when the LLM omitted them. When every attempt fails it
 * returns the **deterministic fallback**: a readable table when the turn produced
 * structured rows, plaintext only when there is no structure at all (the legacy
 * carve-out — a failed `/format chart` on id/code/name rows degrades to a table,
 * never a `str(dict)` dump).
 *
 * @param maxRetries number of retries after the first attempt (default 2 → 3 total),
 *                   mirroring `BP_TEST_FORMAT_NODE_RETRIES = 2`.
 */
class FormatCatalog(
    private val formatter: StructuredFormatter,
    private val maxRetries: Int = 2,
) {
    suspend fun format(request: FormatRequest): FormatResult {
        var priorError: String? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                return realise(formatter.pick(request, priorError))
            } catch (e: FormatToolException) {
                log.debug { "format attempt $attempt failed (${e.reason}): ${e.message}" }
                priorError = "${e.reason}: ${e.message}"
            }
        }
        log.info { "format catalog exhausted ${maxRetries + 1} attempts — deterministic fallback" }
        return deterministicFallback(request)
    }

    private fun realise(call: RenderCall): FormatResult =
        when (call) {
            is RenderCall.Plaintext ->
                FormatResult(
                    kind = FormatKind.PLAINTEXT,
                    text = call.text,
                    contentJson = null,
                    format = FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT).build(),
                )

            is RenderCall.Markdown ->
                FormatResult(
                    // Markdown re-parse trap: the source is carried VERBATIM. No
                    // escaping, no round-trip through a JSON content payload.
                    kind = FormatKind.MARKDOWN,
                    text = call.text,
                    contentJson = null,
                    format =
                        FormatSpec
                            .newBuilder()
                            .setKind(FormatKind.MARKDOWN)
                            .setMarkdown(
                                MarkdownDetails
                                    .newBuilder()
                                    .setAllowMermaid(true)
                                    .setAllowImages(false),
                            ).build(),
                )

            is RenderCall.Table -> tableResult(call.text, call.content, call.details)
            is RenderCall.Chart -> chartResult(call)
        }

    private fun tableResult(
        text: String?,
        content: JsonElement,
        details: TableDetailsInput,
        fellBack: Boolean = false,
        fallbackFrom: FormatKind = FormatKind.FORMAT_KIND_UNSPECIFIED,
    ): FormatResult {
        val headers =
            details.headers?.map {
                TableHeader
                    .newBuilder()
                    .setName(it.name)
                    .setTitle(it.title)
                    .build()
            }
                ?: inferTableHeaders(content).map {
                    TableHeader
                        .newBuilder()
                        .setName(it.name)
                        .setTitle(it.title)
                        .build()
                }

        val supplied =
            details.columns.orEmpty().mapValues { (_, spec) ->
                ColumnDirective(spec.alignment, spec.width, spec.hidden, spec.format)
            }
        val columns = inferColumnDirectives(content, supplied)

        val tableBuilder =
            TableDetails
                .newBuilder()
                .addAllHeaders(headers)
        details.alternateColors?.let { tableBuilder.alternateColors = it }
        columns.forEach { (name, d) -> tableBuilder.putColumns(name, d.toProto()) }

        return FormatResult(
            kind = FormatKind.TABLE,
            text = text,
            contentJson = content.toString(),
            format =
                FormatSpec
                    .newBuilder()
                    .setKind(FormatKind.TABLE)
                    .setTable(tableBuilder)
                    .build(),
            fellBack = fellBack,
            fallbackFrom = fallbackFrom,
        )
    }

    private fun chartResult(call: RenderCall.Chart): FormatResult {
        val intent = call.intent.toProto()
        val vegaSpec = VegaLiteCompiler.compile(intent, call.content).toString()
        return FormatResult(
            kind = FormatKind.CHART,
            text = call.text,
            contentJson = call.content.toString(),
            format =
                FormatSpec
                    .newBuilder()
                    .setKind(FormatKind.CHART)
                    .setChart(
                        ChartIntentDetails
                            .newBuilder()
                            .setIntent(intent)
                            .setVegaLiteSpecJson(vegaSpec),
                    ).build(),
        )
    }

    private fun deterministicFallback(request: FormatRequest): FormatResult {
        val rows = request.rows
        val structured =
            (rows is JsonArray && rows.isNotEmpty()) || (rows is JsonObject && rows.isNotEmpty())
        val from = request.desiredKind ?: FormatKind.FORMAT_KIND_UNSPECIFIED
        return if (structured) {
            tableResult(
                text = null,
                content = rows!!,
                details = TableDetailsInput(alternateColors = "Rows"),
                fellBack = true,
                fallbackFrom = from,
            )
        } else {
            FormatResult(
                kind = FormatKind.PLAINTEXT,
                text = request.answerText,
                contentJson = null,
                format = FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT).build(),
                fellBack = true,
                fallbackFrom = from,
            )
        }
    }
}

private fun ColumnDirective.toProto(): TableColumnSpec {
    val b = TableColumnSpec.newBuilder()
    alignment?.let { b.alignment = it }
    width?.let { b.width = it }
    hidden?.let { b.hidden = it }
    format?.let { b.format = it }
    return b.build()
}

private fun ChartIntentInput.toProto(): ChartIntent {
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
    seriesField?.let { b.seriesField = it }
    return b.build()
}
