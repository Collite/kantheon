package org.tatrman.kantheon.pythia.dataplane

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.serialization.json.JsonArray
import org.tatrman.metis.v1.DiagnoseRequest
import org.tatrman.metis.v1.DiagnoseResult
import org.tatrman.metis.v1.DiagnosticCheck
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.FitResult
import org.tatrman.metis.v1.GetStatusResponse
import org.tatrman.metis.v1.MetisServiceGrpcKt
import org.tatrman.metis.v1.ModelKind
import org.tatrman.metis.v1.ProjectRequest
import org.tatrman.metis.v1.ProjectResult
import org.tatrman.metis.v1.SimulateScenarioRequest

/**
 * In-process Metis fixture-server (Stage 4.2 — the `MetisClient` gRPC spec). Returns
 * the pinned golden ARIMA fit + forecast; flags drive the NOT_FOUND (re-fittable) and
 * FAILED_PRECONDITION error paths. Live Metis is the integration suite.
 */
class FixtureMetisService(
    var failProjectPrecondition: Boolean = false,
    var notFoundProjectModel: String? = null,
) : MetisServiceGrpcKt.MetisServiceCoroutineImplBase() {
    val calls = mutableListOf<String>()

    override suspend fun fit(request: FitRequest): FitResult {
        calls += "fit:${request.modelName}:${request.modelKind}"
        return FitResult
            .newBuilder()
            .setModelName(request.modelName)
            .setModelKind(
                if (request.modelKind ==
                    ModelKind.MODEL_KIND_UNSPECIFIED
                ) {
                    ModelKind.ARIMA
                } else {
                    request.modelKind
                },
            ).setChosenOrder("(1,1,1)(0,1,1,12)")
            .setAic(-123.456789)
            .setInputRows(36)
            .build()
    }

    override suspend fun project(request: ProjectRequest): ProjectResult {
        calls += "project:${request.modelName}"
        if (request.modelName == notFoundProjectModel) {
            throw StatusException(Status.NOT_FOUND.withDescription("model '${request.modelName}' not in session"))
        }
        if (failProjectPrecondition) {
            throw StatusException(Status.FAILED_PRECONDITION.withDescription("horizon precedes series end"))
        }
        return ProjectResult
            .newBuilder()
            .setOutputDf(request.outputDf)
            .setRows(13)
            .build()
    }

    override suspend fun simulateScenario(request: SimulateScenarioRequest): ProjectResult {
        calls += "simulate:${request.forecastDf}"
        return ProjectResult
            .newBuilder()
            .setOutputDf(request.outputDf)
            .setRows(13)
            .build()
    }

    override suspend fun diagnose(request: DiagnoseRequest): DiagnoseResult {
        calls += "diagnose:${request.modelName}"
        return DiagnoseResult
            .newBuilder()
            .setPass(true)
            .addChecks(
                DiagnosticCheck
                    .newBuilder()
                    .setName("residual_normality")
                    .setPass(true)
                    .setPValue(0.41),
            ).build()
    }

    override suspend fun getStatus(request: org.tatrman.metis.v1.GetStatusRequest): GetStatusResponse {
        calls += "getStatus"
        return GetStatusResponse
            .newBuilder()
            .setSessions(1)
            .setModels(2)
            .build()
    }
}

/**
 * A [MetisClient] fake for the ModelNode / forecast-e2e specs. Returns the pinned
 * golden fit + forecast rows; `exportDataFrame` yields the forecast/scenario rows the
 * ModelNode stores for rendering. [notFoundModels] drives the NOT_FOUND→re-fit path
 * (a `fit` clears the model from the set).
 */
class FakeMetisClient(
    private val forecastRows: JsonArray,
    private val simRows: JsonArray? = null,
    private val diagnosePass: Boolean = true,
    private val notFoundModels: MutableSet<String> = mutableSetOf(),
    private val chosenOrder: String = "(1,1,1)(0,1,1,12)",
    private val aic: Double = -123.456789,
) : MetisClient {
    val fits = mutableListOf<FitRequest>()
    val projects = mutableListOf<ProjectRequest>()
    val simulates = mutableListOf<SimulateScenarioRequest>()
    val diagnoses = mutableListOf<DiagnoseRequest>()
    val imports = mutableListOf<Triple<String, String, Int>>()
    private val exported = mutableMapOf<String, JsonArray>()

    override suspend fun fit(req: FitRequest): FitResult {
        fits += req
        notFoundModels.remove(req.modelName)
        return FitResult
            .newBuilder()
            .setModelName(req.modelName)
            .setModelKind(if (req.modelKind == ModelKind.MODEL_KIND_UNSPECIFIED) ModelKind.ARIMA else req.modelKind)
            .setChosenOrder(chosenOrder)
            .setAic(aic)
            .setInputRows(36)
            .build()
    }

    override suspend fun project(req: ProjectRequest): ProjectResult {
        if (req.modelName in notFoundModels) {
            throw MetisModelNotFoundException(req.modelName, "model '${req.modelName}' not in session")
        }
        projects += req
        exported[req.outputDf] = forecastRows
        return ProjectResult
            .newBuilder()
            .setOutputDf(req.outputDf)
            .setRows(forecastRows.size.toLong())
            .build()
    }

    override suspend fun simulateScenario(req: SimulateScenarioRequest): ProjectResult {
        simulates += req
        exported[req.outputDf] = simRows ?: forecastRows
        return ProjectResult
            .newBuilder()
            .setOutputDf(
                req.outputDf,
            ).setRows((simRows ?: forecastRows).size.toLong())
            .build()
    }

    override suspend fun diagnose(req: DiagnoseRequest): DiagnoseResult {
        diagnoses += req
        return DiagnoseResult
            .newBuilder()
            .setPass(diagnosePass)
            .addChecks(
                DiagnosticCheck
                    .newBuilder()
                    .setName("residual_normality")
                    .setPass(diagnosePass)
                    .setPValue(0.41),
            ).build()
    }

    override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.newBuilder().setSessions(1).build()

    override suspend fun importDataFrame(
        sessionId: String,
        dfName: String,
        rows: JsonArray,
    ) {
        imports += Triple(sessionId, dfName, rows.size)
    }

    override suspend fun exportDataFrame(
        sessionId: String,
        dfName: String,
    ): JsonArray = exported[dfName] ?: JsonArray(emptyList())
}
