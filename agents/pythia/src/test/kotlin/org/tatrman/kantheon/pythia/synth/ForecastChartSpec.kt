package org.tatrman.kantheon.pythia.synth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.pythia.executor.NodeContext
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.RenderNode
import org.tatrman.kantheon.envelope.v1.BlockRole

/**
 * Stage 4.2 T5 — the CHART RenderNode renders a forecast point + CI bands as a
 * ChartBlock: a line intent with the CI-band columns as series, the band numbers
 * carried in the block payload.
 */
class ForecastChartSpec :
    StringSpec({

        "a forecast handle renders to a ChartBlock with the CI-band series present" {
            runTest {
                val handles = HandleTable()
                val forecast =
                    Json.parseToJsonElement(
                        """[{"date":"2026-12-31","yhat":18.2,"yhat_lower":16.4,"yhat_upper":20.0}]""",
                    ) as JsonArray
                handles.putWorkerDf("h-N4", workerPod = "metis", sessionId = "inv-1", dfName = "N4", rows = forecast)

                val chartNode =
                    PlanNode
                        .newBuilder()
                        .setNodeId("N5")
                        .setRender(
                            RenderNode
                                .newBuilder()
                                .setKind(RenderNode.RenderKind.RENDER_CHART)
                                .addInputHandleIds("h-N4")
                                .setBlockRole(BlockRole.PRIMARY)
                                .setCaption("Year-end margin forecast"),
                        ).build()

                RenderNodeExecutor(ScriptedPromptExecutor(emptyList()))
                    .execute(chartNode, NodeContext(handles, "tok"))

                val block = handles.blocks().single()
                block.format.kind shouldBe FormatKind.CHART
                block.format.chart.intent.kind shouldBe "line"
                block.format.chart.intent.x shouldBe "date"
                block.format.chart.intent.yList shouldContainAll listOf("yhat", "yhat_lower", "yhat_upper")
                block.contentJson shouldContain "16.4"
                block.contentJson shouldContain "20.0"
            }
        }
    })
