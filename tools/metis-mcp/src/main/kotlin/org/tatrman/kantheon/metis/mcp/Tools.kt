package org.tatrman.kantheon.metis.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.metis.mcp.client.MetisGrpcClient
import org.tatrman.metis.v1.ArimaParams
import org.tatrman.metis.v1.ArrowChunk
import org.tatrman.metis.v1.DiagnoseRequest
import org.tatrman.metis.v1.DropRequest
import org.tatrman.metis.v1.ExportRequest
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.ImportHeader
import org.tatrman.metis.v1.LinearParams
import org.tatrman.metis.v1.ModelKind
import org.tatrman.metis.v1.ProjectRequest
import org.tatrman.metis.v1.SimulateScenarioRequest
import java.util.Base64

class Tools(
    private val metisGrpcClient: MetisGrpcClient? = null,
) {
    private val logger = LoggerFactory.getLogger(Tools::class.java)

    private fun args(request: CallToolRequest): JsonObject? = request.params.arguments

    private fun strArg(
        args: JsonObject?,
        key: String,
    ): String? = args?.get(key)?.let { (it as? JsonPrimitive)?.content }

    private fun intArg(
        args: JsonObject?,
        key: String,
    ): Int? = strArg(args, key)?.toIntOrNull()

    private fun doubleArg(
        args: JsonObject?,
        key: String,
    ): Double? = strArg(args, key)?.toDoubleOrNull()

    private fun strList(
        args: JsonObject?,
        key: String,
    ): List<String> =
        (args?.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()

    private fun errStructured(
        message: String,
        errorCode: String = "EXECUTION_ERROR",
    ) = buildJsonObject {
        put("errorCode", errorCode)
        put("error", message)
        put("message", message)
        put("extras", buildJsonObject { })
    }

    private fun notWiredResult(toolName: String): CallToolResult {
        val msg = "$toolName requires the gRPC metis client; configure metis.host/port to enable."
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "GRPC_NOT_CONFIGURED"),
        )
    }

    private fun errorResult(
        toolName: String,
        message: String,
    ): CallToolResult {
        logger.info("{} completed | exception | error={}", toolName, message.take(100))
        return CallToolResult(
            content = listOf(TextContent(text = message)),
            isError = true,
            structuredContent = errStructured(message),
        )
    }

    private fun missingArgResult(
        toolName: String,
        argName: String,
    ): CallToolResult {
        val msg = "Missing required argument: $argName"
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "MISSING_ARG"),
        )
    }

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    val modelFitTool =
        Tool(
            name = "model.fit",
            description = "Fit a statistical model (LINEAR, ARIMA, PROPHET) on a series in the Metis workspace.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("modelKind") {
                                put("type", "string")
                                put("description", "LINEAR | ARIMA | PROPHET")
                            }
                            putJsonObject("modelName") {
                                put("type", "string")
                                put("description", "Name to store the fitted model under")
                            }
                            putJsonObject("inputDf") {
                                put("type", "string")
                                put("description", "Name of the DataFrame in the session workspace to fit on")
                            }
                            putJsonObject("inlineArrowIpc") {
                                put("type", "string")
                                put("description", "Base64-encoded Arrow IPC bytes (alternative to inputDf)")
                            }
                            putJsonObject("xCols") {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                                put("description", "Regressor column names (LINEAR only)")
                            }
                            putJsonObject("yCol") {
                                put("type", "string")
                                put("description", "Target column name (LINEAR only)")
                            }
                            putJsonObject("arimaSeasonality") {
                                put("type", "integer")
                                put("description", "ARIMA seasonal period")
                            }
                            putJsonObject("arimaOrder") {
                                put("type", "string")
                                put("description", "Fixed ARIMA order e.g. '1,1,1'")
                            }
                            putJsonObject("arimaMaxOrder") {
                                put("type", "integer")
                                put("description", "Max ARIMA order for auto-selection")
                            }
                        },
                    required = listOf("sessionId", "modelKind", "modelName"),
                ),
        )

    val modelDiagnoseTool =
        Tool(
            name = "model.diagnose",
            description = "Run diagnostic checks on a fitted model in the Metis session workspace.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("modelName") {
                                put("type", "string")
                                put("description", "Name of the fitted model")
                            }
                        },
                    required = listOf("sessionId", "modelName"),
                ),
        )

    val modelProjectTool =
        Tool(
            name = "model.project",
            description = "Generate a forecast from a fitted model for a given horizon.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("modelName") {
                                put("type", "string")
                                put("description", "Name of the fitted model")
                            }
                            putJsonObject("horizon") {
                                put("type", "string")
                                put("description", "Forecast horizon e.g. '+12'")
                            }
                            putJsonObject("confidenceLevel") {
                                put("type", "number")
                                put("description", "Confidence level (0.0–1.0, default 0.95)")
                            }
                            putJsonObject("outputDf") {
                                put("type", "string")
                                put("description", "Name to store the forecast DataFrame under")
                            }
                        },
                    required = listOf("sessionId", "modelName", "horizon", "outputDf"),
                ),
        )

    val modelSimulateTool =
        Tool(
            name = "model.simulate",
            description = "Apply scenario deltas to an existing forecast DataFrame without re-fitting.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("forecastDf") {
                                put("type", "string")
                                put("description", "Name of the forecast DataFrame in the workspace")
                            }
                            putJsonObject("deltasJson") {
                                put("type", "string")
                                put("description", "JSON string of scenario deltas")
                            }
                            putJsonObject("outputDf") {
                                put("type", "string")
                                put("description", "Name to store the adjusted forecast under")
                            }
                        },
                    required = listOf("sessionId", "forecastDf", "deltasJson", "outputDf"),
                ),
        )

    val dataImportTool =
        Tool(
            name = "data.import",
            description = "Import a DataFrame into the Metis session workspace from base64-encoded Arrow IPC bytes.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("dfName") {
                                put("type", "string")
                                put("description", "Name to store the DataFrame under")
                            }
                            putJsonObject("inlineArrowIpc") {
                                put("type", "string")
                                put("description", "Base64-encoded Arrow IPC bytes")
                            }
                        },
                    required = listOf("sessionId", "dfName", "inlineArrowIpc"),
                ),
        )

    val dataExportTool =
        Tool(
            name = "data.export",
            description = "Export a DataFrame from the Metis session workspace as base64-encoded Arrow IPC bytes.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("dfName") {
                                put("type", "string")
                                put("description", "Name of the DataFrame in the workspace")
                            }
                        },
                    required = listOf("sessionId", "dfName"),
                ),
        )

    val dataDropTool =
        Tool(
            name = "data.drop",
            description = "Drop a named entry (DataFrame or model) from the Metis session workspace.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject("sessionId") {
                                put("type", "string")
                                put("description", "Session workspace ID")
                            }
                            putJsonObject("name") {
                                put("type", "string")
                                put("description", "Name of the DataFrame or model to drop")
                            }
                        },
                    required = listOf("sessionId", "name"),
                ),
        )

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    suspend fun modelFitCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("model.fit")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("model.fit", "sessionId")
        val modelKindStr = strArg(a, "modelKind") ?: return missingArgResult("model.fit", "modelKind")
        val modelName = strArg(a, "modelName") ?: return missingArgResult("model.fit", "modelName")
        val modelKind =
            when (modelKindStr.uppercase()) {
                "LINEAR" -> ModelKind.LINEAR
                "ARIMA" -> ModelKind.ARIMA
                "PROPHET" -> ModelKind.PROPHET
                else -> ModelKind.MODEL_KIND_UNSPECIFIED
            }
        return try {
            val builder =
                FitRequest
                    .newBuilder()
                    .setSessionId(sessionId)
                    .setModelKind(modelKind)
                    .setModelName(modelName)
            strArg(a, "inputDf")?.let { builder.setInputDf(it) }
            strArg(a, "inlineArrowIpc")?.let {
                builder.setInlineArrowIpc(
                    com.google.protobuf.ByteString
                        .copyFrom(Base64.getDecoder().decode(it)),
                )
            }
            val xCols = strList(a, "xCols")
            val yCol = strArg(a, "yCol")
            if (xCols.isNotEmpty() || yCol != null) {
                builder.setLinear(
                    LinearParams
                        .newBuilder()
                        .addAllXCols(xCols)
                        .apply { yCol?.let { setYCol(it) } }
                        .build(),
                )
            }
            val arimaSeasonality = intArg(a, "arimaSeasonality")
            val arimaOrder = strArg(a, "arimaOrder")
            val arimaMaxOrder = intArg(a, "arimaMaxOrder")
            if (arimaSeasonality != null || arimaOrder != null || arimaMaxOrder != null) {
                builder.setArima(
                    ArimaParams
                        .newBuilder()
                        .apply { arimaSeasonality?.let { setSeasonality(it) } }
                        .apply { arimaOrder?.let { setOrder(it) } }
                        .apply { arimaMaxOrder?.let { setMaxOrder(it) } }
                        .build(),
                )
            }
            val result = grpc.fit(builder.build())
            val structured =
                buildJsonObject {
                    put("modelName", JsonPrimitive(result.modelName))
                    put("modelKind", JsonPrimitive(result.modelKind.name))
                    put("chosenOrder", JsonPrimitive(result.chosenOrder))
                    put("aic", JsonPrimitive(result.aic))
                    put("logLikelihood", JsonPrimitive(result.logLikelihood))
                    put("inputRows", JsonPrimitive(result.inputRows))
                    put("fitDurationMs", JsonPrimitive(result.fitDurationMs))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("model.fit completed | success | model={} | isError=false", modelName)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing model.fit", e)
            errorResult("model.fit", "Fit failed: ${e.message}")
        }
    }

    suspend fun modelDiagnoseCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("model.diagnose")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("model.diagnose", "sessionId")
        val modelName = strArg(a, "modelName") ?: return missingArgResult("model.diagnose", "modelName")
        return try {
            val result =
                grpc.diagnose(
                    DiagnoseRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setModelName(modelName)
                        .build(),
                )
            val checksArray =
                buildJsonArray {
                    for (c in result.checksList) {
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
            val structured =
                buildJsonObject {
                    put("pass", JsonPrimitive(result.pass))
                    put("checks", checksArray)
                }
            val text = McpJson.encodeToString(structured)
            logger.info("model.diagnose completed | success | model={} | pass={}", modelName, result.pass)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing model.diagnose", e)
            errorResult("model.diagnose", "Diagnose failed: ${e.message}")
        }
    }

    suspend fun modelProjectCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("model.project")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("model.project", "sessionId")
        val modelName = strArg(a, "modelName") ?: return missingArgResult("model.project", "modelName")
        val horizon = strArg(a, "horizon") ?: return missingArgResult("model.project", "horizon")
        val outputDf = strArg(a, "outputDf") ?: return missingArgResult("model.project", "outputDf")
        return try {
            val builder =
                ProjectRequest
                    .newBuilder()
                    .setSessionId(sessionId)
                    .setModelName(modelName)
                    .setHorizon(horizon)
                    .setOutputDf(outputDf)
            doubleArg(a, "confidenceLevel")?.let { builder.setConfidenceLevel(it) }
            val result = grpc.project(builder.build())
            val structured =
                buildJsonObject {
                    put("outputDf", JsonPrimitive(result.outputDf))
                    put("schemaFingerprint", JsonPrimitive(result.schemaFingerprint))
                    put("rows", JsonPrimitive(result.rows))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("model.project completed | success | model={} | rows={}", modelName, result.rows)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing model.project", e)
            errorResult("model.project", "Project failed: ${e.message}")
        }
    }

    suspend fun modelSimulateCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("model.simulate")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("model.simulate", "sessionId")
        val forecastDf = strArg(a, "forecastDf") ?: return missingArgResult("model.simulate", "forecastDf")
        val deltasJson = strArg(a, "deltasJson") ?: return missingArgResult("model.simulate", "deltasJson")
        val outputDf = strArg(a, "outputDf") ?: return missingArgResult("model.simulate", "outputDf")
        return try {
            val result =
                grpc.simulateScenario(
                    SimulateScenarioRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setForecastDf(forecastDf)
                        .setDeltasJson(deltasJson)
                        .setOutputDf(outputDf)
                        .build(),
                )
            val structured =
                buildJsonObject {
                    put("outputDf", JsonPrimitive(result.outputDf))
                    put("schemaFingerprint", JsonPrimitive(result.schemaFingerprint))
                    put("rows", JsonPrimitive(result.rows))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("model.simulate completed | success | outputDf={} | rows={}", outputDf, result.rows)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing model.simulate", e)
            errorResult("model.simulate", "Simulate failed: ${e.message}")
        }
    }

    suspend fun dataImportCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("data.import")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("data.import", "sessionId")
        val dfName = strArg(a, "dfName") ?: return missingArgResult("data.import", "dfName")
        val ipcB64 = strArg(a, "inlineArrowIpc") ?: return missingArgResult("data.import", "inlineArrowIpc")
        return try {
            val ipcBytes = Base64.getDecoder().decode(ipcB64)
            val chunks =
                flow {
                    emit(
                        ArrowChunk
                            .newBuilder()
                            .setHeader(
                                ImportHeader
                                    .newBuilder()
                                    .setSessionId(sessionId)
                                    .setDfName(dfName)
                                    .build(),
                            ).setIpcPayload(
                                com.google.protobuf.ByteString
                                    .copyFrom(ipcBytes),
                            ).build(),
                    )
                }
            val result = grpc.importDataFrame(chunks)
            val structured =
                buildJsonObject {
                    put("dfName", JsonPrimitive(result.dfName))
                    put("schemaFingerprint", JsonPrimitive(result.schemaFingerprint))
                    put("rows", JsonPrimitive(result.rows))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("data.import completed | success | dfName={} | rows={}", dfName, result.rows)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing data.import", e)
            errorResult("data.import", "Import failed: ${e.message}")
        }
    }

    suspend fun dataExportCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("data.export")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("data.export", "sessionId")
        val dfName = strArg(a, "dfName") ?: return missingArgResult("data.export", "dfName")
        return try {
            val chunks = mutableListOf<ByteArray>()
            grpc
                .exportDataFrame(
                    ExportRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setDfName(dfName)
                        .build(),
                ).collect { chunk ->
                    chunks.add(chunk.ipcPayload.toByteArray())
                }
            val combined = chunks.fold(ByteArray(0)) { acc, b -> acc + b }
            val b64 = Base64.getEncoder().encodeToString(combined)
            val structured =
                buildJsonObject {
                    put("dfName", JsonPrimitive(dfName))
                    put("inlineArrowIpc", JsonPrimitive(b64))
                    put("sizeBytes", JsonPrimitive(combined.size))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("data.export completed | success | dfName={} | bytes={}", dfName, combined.size)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing data.export", e)
            errorResult("data.export", "Export failed: ${e.message}")
        }
    }

    suspend fun dataDropCallback(request: CallToolRequest): CallToolResult {
        val grpc = metisGrpcClient ?: return notWiredResult("data.drop")
        val a = args(request)
        val sessionId = strArg(a, "sessionId") ?: return missingArgResult("data.drop", "sessionId")
        val name = strArg(a, "name") ?: return missingArgResult("data.drop", "name")
        return try {
            val result =
                grpc.dropWorkspaceEntry(
                    DropRequest
                        .newBuilder()
                        .setSessionId(sessionId)
                        .setName(name)
                        .build(),
                )
            val structured =
                buildJsonObject {
                    put("existed", JsonPrimitive(result.existed))
                    put("name", JsonPrimitive(name))
                }
            val text = McpJson.encodeToString(structured)
            logger.info("data.drop completed | success | name={} | existed={}", name, result.existed)
            CallToolResult(content = listOf(TextContent(text = text)), structuredContent = structured)
        } catch (e: Exception) {
            logger.error("Error executing data.drop", e)
            errorResult("data.drop", "Drop failed: ${e.message}")
        }
    }
}
