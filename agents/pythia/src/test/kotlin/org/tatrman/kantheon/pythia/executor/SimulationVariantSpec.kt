package org.tatrman.kantheon.pythia.executor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.dataplane.FakeMetisClient
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.ModelNode
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Stage 4.2 T4 — the simulation variant: `model.simulate.scenario(forecast=Handle,
 * deltas)` inserts a `SimulateScenario` after the base forecast; the scenario output
 * diverges from the base forecast per the deltas.
 */
class SimulationVariantSpec :
    StringSpec({

        val baseForecast =
            Json.parseToJsonElement("""[{"date":"2026-12-31","yhat":18.2,"yhat_lower":16.4,"yhat_upper":20.0}]""")
                as JsonArray

        // Scenario applies price +20% / volume -5% → a higher margin path.
        val scenarioForecast =
            Json.parseToJsonElement("""[{"date":"2026-12-31","yhat":21.6,"yhat_lower":19.5,"yhat_upper":23.7}]""")
                as JsonArray

        "model.simulate.scenario issues SimulateScenario with the deltas and diverges from the base" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf(
                    "h-N4",
                    workerPod = "metis",
                    sessionId = "inv-1",
                    dfName = "N4",
                    rows = baseForecast,
                )
                val metis = FakeMetisClient(forecastRows = baseForecast, simRows = scenarioForecast)
                val simNode =
                    PlanNode
                        .newBuilder()
                        .setNodeId("N4b")
                        .setModel(
                            ModelNode
                                .newBuilder()
                                .setCapabilityId("model.simulate.scenario")
                                .addInputHandleIds("h-N4")
                                .setParamsJson("""{"deltasJson":"{\"priceDelta\":0.2,\"volumeDelta\":-0.05}"}"""),
                        ).build()

                val result =
                    ModelNodeExecutor(metis).execute(simNode, NodeContext(handles, "tok", sessionId = "inv-1"))

                metis.simulates.single().forecastDf shouldBe "N4"
                metis.simulates.single().deltasJson shouldContain "priceDelta"
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.WORKER_DF
                // The scenario output diverges from the base forecast (21.6 vs 18.2).
                handles.rows("h-N4b")!!.toString() shouldContain "21.6"
                handles.rows("h-N4")!!.toString() shouldContain "18.2"
            }
        }
    })
