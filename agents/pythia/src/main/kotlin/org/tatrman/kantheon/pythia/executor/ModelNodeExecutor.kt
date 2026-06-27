package org.tatrman.kantheon.pythia.executor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.pythia.dataplane.MetisClient
import org.tatrman.kantheon.pythia.dataplane.MetisException
import org.tatrman.kantheon.pythia.dataplane.MetisModelNotFoundException
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.metis.v1.DiagnoseRequest
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.ModelKind
import org.tatrman.metis.v1.ProjectRequest
import org.tatrman.metis.v1.SimulateScenarioRequest

/**
 * Executes a `ModelNode` (Phase 4) on Metis. The design's per-kind vocabulary maps
 * onto Metis's single-tool surface (metis/contracts.md §4) by the capability id:
 *   - `model.fit.<kind>`      → stage input into the Metis session → `Fit`
 *   - `model.project|forecast`→ `Project` → output DF → `WorkerSessionDF` (worker_kind METIS)
 *   - `model.simulate.scenario`→ `SimulateScenario(forecast_df, deltas_json)`
 *   - `model.diagnose`        → `Diagnose(model_name)` (the prose is a downstream ReasoningNode)
 *
 * The model kind travels as an **argument** (params `modelKind` / the capability suffix),
 * not as part of the capability id. A `NOT_FOUND` model is **re-fittable** (re-fit from the
 * node's fit spec, then retry); a `FAILED_PRECONDITION` becomes a PERMANENT failure (→ the
 * hypothesis goes INCONCLUSIVE, the message surfaced Rule-6).
 */
class ModelNodeExecutor(
    private val metis: MetisClient,
) : NodeExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun providerOf(node: PlanNode): String = "metis"

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val model = node.model
        val session = ctx.sessionId.ifBlank { "s" }
        val params = parseParams(model.paramsJson)
        val cap = model.capabilityId.lowercase()
        return when {
            "simulate" in cap -> simulate(node, ctx, session, params)
            "project" in cap || "forecast" in cap -> project(node, ctx, session, params)
            "diagnose" in cap -> diagnose(node, ctx, session)
            "fit" in cap -> fit(node, ctx, session, params)
            else ->
                throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "ModelNode ${node.nodeId}: unknown model capability '${model.capabilityId}'",
                )
        }
    }

    private suspend fun fit(
        node: PlanNode,
        ctx: NodeContext,
        session: String,
        params: JsonObject,
    ): NodeResult {
        val inputId =
            node.model.inputHandleIdsList.firstOrNull()
                ?: throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "ModelNode ${node.nodeId}: fit needs an input handle",
                )
        val inputDf = stageInput(inputId, node.nodeId, ctx, session)
        val kind = modelKind(node.model.capabilityId, params)
        val fit =
            metis.fit(
                FitRequest
                    .newBuilder()
                    .setSessionId(session)
                    .setModelKind(kind)
                    .setInputDf(inputDf)
                    .setModelName(node.nodeId)
                    .build(),
            )
        // A fitted model is in-session: represent it as a WorkerSessionDF (worker_kind METIS)
        // pointer keyed (session, node_id) — downstream project/diagnose reference it.
        val handle =
            ctx.handles.putWorkerDf(
                handleId = "h-${node.nodeId}",
                workerPod = "metis",
                sessionId = session,
                dfName = node.nodeId,
                rowCountEst = fit.inputRows,
                schemaJson = """{"chosenOrder":"${fit.chosenOrder}","aic":${fit.aic}}""",
            )
        return NodeResult(handle, fit.inputRows, costUsd = 0.0)
    }

    private suspend fun project(
        node: PlanNode,
        ctx: NodeContext,
        session: String,
        params: JsonObject,
    ): NodeResult {
        val modelName = modelRef(node, ctx)
        val request =
            ProjectRequest
                .newBuilder()
                .setSessionId(session)
                .setModelName(modelName)
                .setHorizon(params.str("horizon") ?: "")
                .setConfidenceLevel(params.dbl("confidenceLevel") ?: 0.90)
                .setOutputDf(node.nodeId)
                .build()
        val result =
            try {
                metis.project(request)
            } catch (e: MetisModelNotFoundException) {
                // Re-fittable: re-fit from the node's fit spec (params), then retry the projection.
                // `refit` may itself throw NodeExecutionException(PERMANENT) — that propagates as-is.
                // The retried projection is mapped exactly like the first call: a second NOT_FOUND or
                // a precondition failure is a PERMANENT node failure, never a raw RuntimeException escape.
                refit(session, modelName, params)
                try {
                    metis.project(request)
                } catch (retry: MetisModelNotFoundException) {
                    throw NodeExecutionException(
                        FailureKind.PERMANENT,
                        "ModelNode ${node.nodeId}: model '$modelName' still missing after re-fit",
                    )
                } catch (retry: MetisException) {
                    throw NodeExecutionException(
                        FailureKind.PERMANENT,
                        "ModelNode ${node.nodeId}: ${retry.code} — ${retry.message}",
                    )
                }
            } catch (e: MetisException) {
                throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "ModelNode ${node.nodeId}: ${e.code} — ${e.message}",
                )
            }
        val rows = metis.exportDataFrame(session, result.outputDf)
        val handle =
            ctx.handles.putWorkerDf(
                handleId = "h-${node.nodeId}",
                workerPod = "metis",
                sessionId = session,
                dfName = result.outputDf,
                rowCountEst = result.rows,
                rows = rows,
            )
        return NodeResult(handle, result.rows, costUsd = 0.0)
    }

    private suspend fun simulate(
        node: PlanNode,
        ctx: NodeContext,
        session: String,
        params: JsonObject,
    ): NodeResult {
        val forecastDf = modelRef(node, ctx)
        val deltas = params.str("deltasJson") ?: node.model.paramsJson.ifBlank { "{}" }
        val result =
            metis.simulateScenario(
                SimulateScenarioRequest
                    .newBuilder()
                    .setSessionId(session)
                    .setForecastDf(forecastDf)
                    .setDeltasJson(deltas)
                    .setOutputDf(node.nodeId)
                    .build(),
            )
        val rows = metis.exportDataFrame(session, result.outputDf)
        val handle =
            ctx.handles.putWorkerDf(
                handleId = "h-${node.nodeId}",
                workerPod = "metis",
                sessionId = session,
                dfName = result.outputDf,
                rowCountEst = result.rows,
                rows = rows,
            )
        return NodeResult(handle, result.rows, costUsd = 0.0)
    }

    private suspend fun diagnose(
        node: PlanNode,
        ctx: NodeContext,
        session: String,
    ): NodeResult {
        val modelName = modelRef(node, ctx)
        val result =
            metis.diagnose(
                DiagnoseRequest
                    .newBuilder()
                    .setSessionId(session)
                    .setModelName(modelName)
                    .build(),
            )
        // The deterministic check data is stored as a snapshot; the prose is a downstream ReasoningNode.
        val rows =
            buildJsonArray {
                result.checksList.forEach { c ->
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(c.name))
                            put("pass", JsonPrimitive(c.pass))
                            put("statistic", JsonPrimitive(c.statistic))
                            put("pValue", JsonPrimitive(c.pValue))
                            put("detail", JsonPrimitive(c.detail))
                        },
                    )
                }
            }
        val handle = ctx.handles.putSnapshot("h-${node.nodeId}", rows).handle
        val warnings = if (!result.pass) listOf("diagnostics FAILED for $modelName") else emptyList()
        return NodeResult(handle, rows.size.toLong(), costUsd = 0.0, warnings = warnings)
    }

    /** The Metis-session df name to push the input handle to (staging a Pythia-internal source). */
    private suspend fun stageInput(
        inputId: String,
        nodeId: String,
        ctx: NodeContext,
        session: String,
    ): String {
        val handle = ctx.handles.get(inputId) ?: return inputId
        return when (handle.kindCase) {
            Handle.KindCase.WORKER_DF -> handle.workerDf.dfName // already staged (Charon Stage → METIS)
            Handle.KindCase.PG_SNAPSHOT, Handle.KindCase.LIVE_QUERY -> {
                val rows = ctx.handles.rows(inputId) ?: JsonArray(emptyList())
                val dfName = "src-$nodeId"
                metis.importDataFrame(session, dfName, rows)
                dfName
            }
            else -> inputId
        }
    }

    /** The model/forecast handle a project/simulate/diagnose node references (its session df name). */
    private fun modelRef(
        node: PlanNode,
        ctx: NodeContext,
    ): String {
        val id =
            node.model.inputHandleIdsList.firstOrNull()
                ?: throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "ModelNode ${node.nodeId}: needs an input handle",
                )
        val handle = ctx.handles.get(id)
        return handle?.workerDf?.dfName?.ifBlank { id } ?: id
    }

    private suspend fun refit(
        session: String,
        modelName: String,
        params: JsonObject,
    ) {
        val inputDf =
            params.str("refitInputDf")
                ?: throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "model '$modelName' not found and no refit spec (refitInputDf) to rebuild it",
                )
        metis.fit(
            FitRequest
                .newBuilder()
                .setSessionId(session)
                .setModelKind(modelKind("", params))
                .setInputDf(inputDf)
                .setModelName(modelName)
                .build(),
        )
    }

    private fun modelKind(
        capabilityId: String,
        params: JsonObject,
    ): ModelKind {
        val hint = (params.str("modelKind") ?: capabilityId).lowercase()
        return when {
            "prophet" in hint -> ModelKind.PROPHET
            "linear" in hint -> ModelKind.LINEAR
            else -> ModelKind.ARIMA
        }
    }

    private fun parseParams(paramsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(paramsJson.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.doubleOrNull
}
