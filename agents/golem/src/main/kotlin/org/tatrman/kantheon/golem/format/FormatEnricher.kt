package org.tatrman.kantheon.golem.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.envelope.render.charts.VegaLiteCompiler
import org.tatrman.kantheon.envelope.v1.ChartIntent
import org.tatrman.kantheon.envelope.v1.ChartIntentDetails
import org.tatrman.kantheon.envelope.v1.Chip
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PromptChip
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding

/**
 * The format pipeline's envelope enrichment (S3.1 §10 Δ2/Δ5). Takes the executor's
 * base TABLE envelope (bubble/turn/content/typed table already set) and layers on:
 *   * **kind inference** (`result_kind_hint` > amend-on-compare > table) — promotes
 *     to a CHART (compiled Vega-Lite) when inferred and the rows support it;
 *   * **chips** — heuristic + pattern_derived + (gated) llm_topup;
 *   * **drilldowns** — explicit_ttr + auto_overlap;
 *   * **`current_view`** (`total_rows`) and **`losing_plan_summary`**.
 */
class FormatEnricher(
    private val config: FormatConfig = FormatConfig(),
    private val llmTopup: LlmTopupChips = LlmTopupChips(FormatConfig()),
) {
    suspend fun enrich(
        base: FormatEnvelope.Builder,
        rows: JsonArray,
        request: GolemRequest,
        model: ModelSnapshot?,
        plan: MiniPlan,
        rowCount: Long,
        priorBindings: List<EntityBinding> = emptyList(),
    ): FormatEnvelope {
        val primary = plan.nodesList.firstOrNull { it.hasQuery() && it.query.hasPatternId() }?.query
        val pickedId = primary?.patternId
        val bindings = request.resolvedIntent.bindingsList

        // 1. Kind inference → keep TABLE (already set) or promote to CHART.
        val hint =
            pickedId
                ?.let {
                    model
                        ?.patternQuery(
                            it,
                        )?.queryDescriptor
                        ?.resultKindHint
                }?.takeIf { it.isNotBlank() }
        val inferred = KindInference.infer(hint, plan.source, bindings, priorBindings, config.chartOnCompare)
        if (inferred.kind == FormatKind.CHART) applyChart(base, rows, inferred)

        // 2. Chips — heuristic, pattern_derived, then (gated) llm_topup. Deduplicated across all
        //    three sources (the LLM top-up can echo a heuristic/pattern chip) by prompt text.
        val chips = mutableListOf<PromptChip>()
        chips += HeuristicChips.derive(rows, rowCount.toInt())
        chips += PatternDerivedChips.derive(pickedId, bindings, model)
        chips += llmTopup.derive(request.question, chips.size)
        chips
            .distinctBy {
                it.prompt
                    .ifBlank { it.display }
                    .trim()
                    .lowercase()
            }.forEach { base.addChips(Chip.newBuilder().setPrompt(it)) }

        // 3. Drilldowns.
        Drilldowns.derive(pickedId, rows, model).forEach { base.addDrilldowns(it) }

        // 4. current_view + losing_plan_summary.
        val cv = CurrentView.newBuilder().setBubbleId(base.bubbleId).setTotalRows(rowCount)
        pickedId?.let { cv.patternId = it }
        primary?.paramsJson?.takeIf { it.isNotBlank() && it != "{}" }?.let { cv.argsJson = it }
        base.currentView = cv.build()
        if (plan.hasLosingPlanSummary() && plan.losingPlanSummary.isNotBlank()) {
            base.losingPlanSummary = plan.losingPlanSummary
        }
        return base.build()
    }

    /** Promote the envelope to a compiled bar chart when the rows carry an x + ≥1 numeric y. */
    private fun applyChart(
        base: FormatEnvelope.Builder,
        rows: JsonArray,
        inferred: InferredKind,
    ) {
        val cols = RowUtil.columnNames(rows)
        if (cols.isEmpty()) return
        val numeric = cols.filter { isNumericColumn(rows, it) }
        val x = cols.firstOrNull { it !in numeric } ?: cols.first()
        val y = numeric.filter { it != x }
        if (y.isEmpty()) {
            // An inferred CHART that the rows can't actually support stays a TABLE; surface the
            // divergence as a Rule-6 hint rather than silently swallowing the promotion.
            base.addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.WARNING)
                    .setCode("CHART_NOT_RENDERABLE")
                    .setHumanMessage("Výsledek nemá číselnou hodnotu pro graf — zobrazeno jako tabulka."),
            )
            return
        }
        // The amend-on-compare series axis is an entity-type/binding name, not necessarily a result
        // column — resolve it to the actual column (case-insensitively) and drop it if absent, so the
        // chart never references a non-existent field.
        val seriesField = inferred.seriesField?.let { RowUtil.resolveColumn(rows, it) }
        val intent =
            ChartIntent
                .newBuilder()
                .setKind(inferred.chartType ?: "bar")
                .setX(x)
                .addAllY(y)
                .also { b -> seriesField?.let { b.seriesField = it } }
                .build()
        val vega = runCatching { VegaLiteCompiler.compile(intent, rows).toString() }.getOrNull()
        val details =
            ChartIntentDetails
                .newBuilder()
                .setIntent(intent)
                .also { d -> vega?.let { d.vegaLiteSpecJson = it } }
                .also { d -> seriesField?.let { d.seriesField = it } }
        base.format =
            FormatSpec
                .newBuilder()
                .setKind(FormatKind.CHART)
                .setChart(details)
                .build()
    }

    private fun isNumericColumn(
        rows: JsonArray,
        col: String,
    ): Boolean {
        // Skip JSON nulls (a column with a single null cell isn't ipso-facto non-numeric) —
        // matches the table-directive path's null handling (FormatDirectives).
        val values = RowUtil.columnValues(rows, col).filterNot { it is JsonNull }
        if (values.isEmpty()) return false
        return values.all { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toDoubleOrNull() != null }
    }
}
