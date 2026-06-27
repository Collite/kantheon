package org.tatrman.kantheon.envelope.render.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The parsed result of the LLM picking exactly one of the four render tools
 * (port of new-golem v2's `FORMAT_TOOLS` + `tool_choice="any"`; recovered from
 * ai-platform golem `format_catalog.py` @ git 5281954d^). One subtype per tool;
 * the [org.tatrman.kantheon.envelope.render.fallback.StructuredFormatter]
 * returns one of these or throws a [FormatToolException].
 */
sealed interface RenderCall {
    /** `RenderPlaintext` — a short single-paragraph answer. */
    data class Plaintext(
        val text: String,
    ) : RenderCall

    /** `RenderMarkdown` — narrative / lists / headings / code / mermaid. The
     *  [text] is carried VERBATIM into the envelope (markdown re-parse trap). */
    data class Markdown(
        val text: String,
        val openInTabDefaultTitle: String? = null,
    ) : RenderCall

    /** `RenderTable` — a list-of-objects (or single object) to read. [content]
     *  is the rows array/object copied verbatim; [details] carries the LLM's
     *  optional column hints (headers inferred when absent). */
    data class Table(
        val text: String?,
        val content: JsonElement,
        val details: TableDetailsInput = TableDetailsInput(),
    ) : RenderCall

    /** `RenderChart` — a visualisable series. Vega-Lite compilation lands in
     *  Stage 1.2; Stage 1.1 carries the [intent] only. */
    data class Chart(
        val text: String?,
        val content: JsonArray,
        val intent: ChartIntentInput,
    ) : RenderCall
}

/**
 * LLM-facing input shape for `RenderTable.details` (subset of envelope/v1
 * `TableDetails` the catalog tool emits). `paging` / `sort` / `filters` are not
 * modelled here — they are produced by the typed-action routes (Golem Phase 3),
 * not the format LLM.
 */
@Serializable
data class TableDetailsInput(
    @SerialName("alternateColors") val alternateColors: String? = null,
    @SerialName("headers") val headers: List<TableHeaderInput>? = null,
    @SerialName("columns") val columns: Map<String, TableColumnSpecInput>? = null,
)

@Serializable
data class TableHeaderInput(
    val name: String,
    val title: String,
)

@Serializable
data class TableColumnSpecInput(
    val alignment: String? = null,
    val width: Int? = null,
    val hidden: Boolean? = null,
    val format: String? = null,
)

/** LLM-facing input shape for `RenderChart.details` (envelope/v1 `ChartIntent`). */
@Serializable
data class ChartIntentInput(
    val kind: String,
    val title: String? = null,
    val x: String,
    val y: List<String> = emptyList(),
    @SerialName("series_field") val seriesField: String? = null,
    val stacked: Boolean = false,
    @SerialName("show_legend") val showLegend: Boolean = true,
    @SerialName("hide_series") val hideSeries: List<String> = emptyList(),
)
