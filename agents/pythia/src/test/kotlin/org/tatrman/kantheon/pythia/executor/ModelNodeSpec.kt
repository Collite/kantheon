package org.tatrman.kantheon.pythia.executor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.dataplane.FakeMetisClient
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.ModelNode
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.metis.v1.ModelKind

private val FORECAST_ROWS =
    Json.parseToJsonElement(
        """[{"date":"2026-12-31","yhat":18.2,"yhat_lower":16.4,"yhat_upper":20.0}]""",
    ) as JsonArray

/**
 * Stage 4.2 T2 — the ModelNode executor maps the design's per-kind vocabulary onto
 * Metis's single-tool surface: fit issues `Fit` (model kind as an argument), project
 * issues `Project` → a `WorkerSessionDF`, diagnose stores the checks; a `NOT_FOUND`
 * model re-fits.
 */
class ModelNodeSpec :
    StringSpec({

        fun modelNode(
            id: String,
            capability: String,
            inputs: List<String>,
            params: String = "{}",
        ) = PlanNode
            .newBuilder()
            .setNodeId(id)
            .setModel(
                ModelNode
                    .newBuilder()
                    .setCapabilityId(capability)
                    .addAllInputHandleIds(inputs)
                    .setParamsJson(params),
            ).build()

        "model.fit.arima pushes the input series and issues Fit with model kind as an argument" {
            runTest {
                val handles = HandleTable()
                handles.putSnapshot(
                    "h-N1",
                    Json.parseToJsonElement("""[{"month":"2026-01","margin":18.0}]""") as JsonArray,
                )
                val metis = FakeMetisClient(FORECAST_ROWS)
                val result =
                    ModelNodeExecutor(metis).execute(
                        modelNode("N2", "model.fit.arima", listOf("h-N1"), """{"seasonality":12}"""),
                        NodeContext(handles, "tok", sessionId = "inv-1"),
                    )
                metis.fits.single().modelKind shouldBe ModelKind.ARIMA
                metis.fits.single().modelName shouldBe "N2"
                metis.imports shouldHaveSize 1 // the snapshot was pushed to the Metis session
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.WORKER_DF
            }
        }

        "model.project.arima issues Project → a WorkerSessionDF carrying the forecast rows" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-N2", workerPod = "metis", sessionId = "inv-1", dfName = "N2")
                val metis = FakeMetisClient(FORECAST_ROWS)
                val result =
                    ModelNodeExecutor(metis).execute(
                        modelNode("N4", "model.project.arima", listOf("h-N2"), """{"horizon":"2026-12-31"}"""),
                        NodeContext(handles, "tok", sessionId = "inv-1"),
                    )
                metis.projects.single().modelName shouldBe "N2"
                metis.projects.single().horizon shouldBe "2026-12-31"
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.WORKER_DF
                handles.rows("h-N4")!!.toString().contains("16.4") shouldBe true
            }
        }

        "a NOT_FOUND model re-fits then retries the projection" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-N2", workerPod = "metis", sessionId = "inv-1", dfName = "N2")
                val metis = FakeMetisClient(FORECAST_ROWS, notFoundModels = mutableSetOf("N2"))
                ModelNodeExecutor(metis).execute(
                    modelNode("N4", "model.project.arima", listOf("h-N2"), """{"refitInputDf":"series"}"""),
                    NodeContext(handles, "tok", sessionId = "inv-1"),
                )
                metis.fits shouldHaveSize 1 // re-fit happened
                metis.projects shouldHaveSize 1 // retry succeeded
            }
        }

        "model.diagnose stores the check data as a snapshot for a downstream ReasoningNode" {
            runTest {
                val handles = HandleTable()
                handles.putWorkerDf("h-N2", workerPod = "metis", sessionId = "inv-1", dfName = "N2")
                val metis = FakeMetisClient(FORECAST_ROWS)
                val result =
                    ModelNodeExecutor(metis).execute(
                        modelNode("N3", "model.diagnose.arima", listOf("h-N2")),
                        NodeContext(handles, "tok", sessionId = "inv-1"),
                    )
                metis.diagnoses.single().modelName shouldBe "N2"
                result.outputHandle!!.kindCase shouldBe Handle.KindCase.PG_SNAPSHOT
                handles.rows("h-N3")!!.toString().contains("residual_normality") shouldBe true
            }
        }
    })
