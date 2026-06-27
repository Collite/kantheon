package org.tatrman.kantheon.pythia.synth

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.ChartIntent
import org.tatrman.kantheon.envelope.v1.ChartIntentDetails
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.pythia.executor.NodeContext
import org.tatrman.kantheon.pythia.executor.NodeExecutor
import org.tatrman.kantheon.pythia.executor.NodeResult
import org.tatrman.kantheon.pythia.plan.Prompts
import org.tatrman.kantheon.pythia.plan.PythiaModels
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.RenderNode

/**
 * Renders a `RenderNode` to an envelope/v1 `Block` (divergence 6) and stashes it on
 * the handle table for the synthesizer. TABLE wraps the input handle's rows; a
 * NARRATIVE_FRAGMENT is a per-fragment **CHEAP**-tier call (Stage 2.4 T3); CHART
 * (Stage 4.2 T5) renders a forecast time-series with CI bands as a ChartBlock.
 */
class RenderNodeExecutor(
    private val executor: PromptExecutor,
    private val prompts: Prompts = Prompts(),
) : NodeExecutor {
    override fun providerOf(node: PlanNode): String =
        if (node.render.kind == RenderNode.RenderKind.RENDER_NARRATIVE_FRAGMENT) "llm" else "render"

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val render = node.render
        val inputRows = render.inputHandleIdsList.firstNotNullOfOrNull { ctx.handles.rows(it) }
        return when (render.kind) {
            RenderNode.RenderKind.RENDER_TABLE -> {
                val block =
                    Block
                        .newBuilder()
                        .setBlockId("blk-${node.nodeId}")
                        .setRole(render.blockRole)
                        .setFormat(FormatSpec.newBuilder().setKind(FormatKind.TABLE))
                        .apply {
                            if (render.hasCaption()) caption = render.caption
                            inputRows?.let { contentJson = it.toString() }
                        }.build()
                ctx.handles.putBlock(block)
                NodeResult(outputHandle = null, rowCount = inputRows?.size?.toLong() ?: 0, costUsd = 0.0)
            }
            RenderNode.RenderKind.RENDER_NARRATIVE_FRAGMENT -> {
                val text = narrative(ctx.locale, render, inputRows?.toString() ?: "")
                val block =
                    Block
                        .newBuilder()
                        .setBlockId("blk-${node.nodeId}")
                        .setRole(render.blockRole)
                        .setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN))
                        .setText(text)
                        .build()
                ctx.handles.putBlock(block)
                NodeResult(outputHandle = null, rowCount = 0, costUsd = 0.002)
            }
            else -> chart(node, render, inputRows, ctx)
        }
    }

    /**
     * CHART (Stage 4.2 T5) — a forecast time-series with CI bands. The input handle's
     * rows (Metis `Project` output: a time column + point estimate + lower/upper bounds)
     * become a `ChartIntentDetails` (line intent, the CI-band columns as series) carried
     * on the Block's `format` + `content_json`. No data → a placeholder + Rule-6.
     */
    private fun chart(
        node: PlanNode,
        render: RenderNode,
        inputRows: kotlinx.serialization.json.JsonArray?,
        ctx: NodeContext,
    ): NodeResult {
        if (inputRows == null || inputRows.isEmpty()) {
            val placeholder =
                Block
                    .newBuilder()
                    .setBlockId("blk-${node.nodeId}")
                    .setRole(render.blockRole)
                    .setFormat(FormatSpec.newBuilder().setKind(FormatKind.CHART))
                    .setText("[no series data to chart]")
                    .build()
            ctx.handles.putBlock(placeholder)
            return NodeResult(null, 0, 0.0, warnings = listOf("chart node ${node.nodeId} had no input series"))
        }
        val columns = (inputRows.first() as? JsonObject)?.keys?.toList() ?: emptyList()
        val xCol =
            columns.firstOrNull { it.lowercase() in setOf("date", "ds", "period", "month", "t") }
                ?: columns.firstOrNull()
                ?: "x"
        val ySeries = columns.filter { it != xCol }
        val intent =
            ChartIntent
                .newBuilder()
                .setKind("line")
                .apply { if (render.hasCaption()) title = render.caption }
                .setX(xCol)
                .addAllY(ySeries)
                .build()
        val block =
            Block
                .newBuilder()
                .setBlockId("blk-${node.nodeId}")
                .setRole(render.blockRole)
                .setFormat(
                    FormatSpec
                        .newBuilder()
                        .setKind(FormatKind.CHART)
                        .setChart(
                            ChartIntentDetails
                                .newBuilder()
                                .setIntent(intent)
                                .addAllSeries(ySeries)
                                .setRowsJson(inputRows.toString()),
                        ),
                ).setContentJson(inputRows.toString())
                .apply { if (render.hasCaption()) caption = render.caption }
                .build()
        ctx.handles.putBlock(block)
        return NodeResult(null, inputRows.size.toLong(), 0.0)
    }

    private suspend fun narrative(
        locale: String,
        render: RenderNode,
        data: String,
    ): String {
        val template = prompts.load(locale, "narrative-fragment")
        val user = Prompts.substitute(template, mapOf("caption" to render.caption, "data" to data))
        val p =
            prompt("pythia-narrative") {
                system("You write a 1–2 sentence analytical caption. Return only the prose.")
                user(user)
            }
        return executor
            .execute(p, PythiaModels.Cheap, emptyList())
            .filterIsInstance<Message.Assistant>()
            .joinToString(" ") { it.content }
            .ifBlank { render.caption }
    }
}
