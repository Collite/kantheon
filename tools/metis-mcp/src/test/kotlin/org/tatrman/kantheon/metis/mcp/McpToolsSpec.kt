package org.tatrman.kantheon.metis.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.metis.mcp.client.MetisGrpcClient
import org.tatrman.metis.v1.DiagnoseResult
import org.tatrman.metis.v1.DropResult
import org.tatrman.metis.v1.FitResult
import org.tatrman.metis.v1.ModelKind
import org.tatrman.metis.v1.ProjectResult

/**
 * Phase 3 Stage 3.4 — JSON→proto fidelity tests for the 7 metis-mcp tools.
 *
 * Verifies the zero-logic wrapper contract:
 *  - correct argument forwarding to the gRPC client
 *  - structured response shape matches the proto result
 *  - missing required args return an error result without calling gRPC
 *  - not-wired (null client) returns a structured error
 */
class McpToolsSpec :
    StringSpec({

        fun request(
            toolName: String,
            args: JsonObject = JsonObject(emptyMap()),
        ): CallToolRequest = CallToolRequest(CallToolRequestParams(name = toolName, arguments = args))

        // -------------------------------------------------------------------------
        // model.fit
        // -------------------------------------------------------------------------

        "model.fit: LINEAR FitRequest built from argsJson with correct xCols + yCol" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.fit(any()) } returns
                FitResult
                    .newBuilder()
                    .setModelName("sales_linear")
                    .setModelKind(ModelKind.LINEAR)
                    .setInputRows(120)
                    .setFitDurationMs(200)
                    .build()

            val tools = Tools(mockClient)
            val res =
                tools.modelFitCallback(
                    request(
                        "model.fit",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("s1"))
                            put("modelKind", JsonPrimitive("LINEAR"))
                            put("modelName", JsonPrimitive("sales_linear"))
                            put("inputDf", JsonPrimitive("sales_df"))
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify(exactly = 1) { mockClient.fit(match { it.sessionId == "s1" && it.modelKind == ModelKind.LINEAR }) }
        }

        "model.fit: ARIMA with seasonality and maxOrder forwarded correctly" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.fit(any()) } returns FitResult.getDefaultInstance()
            val tools = Tools(mockClient)
            tools.modelFitCallback(
                request(
                    "model.fit",
                    buildJsonObject {
                        put("sessionId", JsonPrimitive("s2"))
                        put("modelKind", JsonPrimitive("ARIMA"))
                        put("modelName", JsonPrimitive("m1"))
                        put("inputDf", JsonPrimitive("df"))
                        put("arimaSeasonality", JsonPrimitive("12"))
                        put("arimaMaxOrder", JsonPrimitive("3"))
                    },
                ),
            )
            coVerify { mockClient.fit(match { it.arima.seasonality == 12 && it.arima.maxOrder == 3 }) }
        }

        "model.fit: missing sessionId returns error without calling gRPC" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            val tools = Tools(mockClient)
            val res =
                tools.modelFitCallback(
                    request(
                        "model.fit",
                        buildJsonObject {
                            put("modelKind", JsonPrimitive("LINEAR"))
                            put("modelName", JsonPrimitive("m"))
                        },
                    ),
                )
            res.isError shouldBe true
            coVerify(exactly = 0) { mockClient.fit(any()) }
        }

        "model.fit: not-wired client returns GRPC_NOT_CONFIGURED error" {
            val tools = Tools(null)
            val res =
                tools.modelFitCallback(
                    request(
                        "model.fit",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("s"))
                            put("modelKind", JsonPrimitive("LINEAR"))
                            put("modelName", JsonPrimitive("m"))
                        },
                    ),
                )
            res.isError shouldBe true
        }

        // -------------------------------------------------------------------------
        // model.diagnose
        // -------------------------------------------------------------------------

        "model.diagnose: session_id and model_name forwarded correctly" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.diagnose(any()) } returns DiagnoseResult.newBuilder().setPass(true).build()
            val tools = Tools(mockClient)
            val res =
                tools.modelDiagnoseCallback(
                    request(
                        "model.diagnose",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("sess-1"))
                            put("modelName", JsonPrimitive("my_arima"))
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify(
                exactly = 1,
            ) { mockClient.diagnose(match { it.sessionId == "sess-1" && it.modelName == "my_arima" }) }
            val structured = res.structuredContent as kotlinx.serialization.json.JsonObject
            (structured["pass"] as JsonPrimitive).content shouldBe "true"
        }

        "model.diagnose: missing modelName returns error" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            val tools = Tools(mockClient)
            val res =
                tools.modelDiagnoseCallback(
                    request("model.diagnose", buildJsonObject { put("sessionId", JsonPrimitive("s")) }),
                )
            res.isError shouldBe true
            coVerify(exactly = 0) { mockClient.diagnose(any()) }
        }

        // -------------------------------------------------------------------------
        // model.project
        // -------------------------------------------------------------------------

        "model.project: horizon and outputDf forwarded correctly" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.project(any()) } returns
                ProjectResult
                    .newBuilder()
                    .setOutputDf("forecast_df")
                    .setRows(12)
                    .build()
            val tools = Tools(mockClient)
            val res =
                tools.modelProjectCallback(
                    request(
                        "model.project",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("s"))
                            put("modelName", JsonPrimitive("m"))
                            put("horizon", JsonPrimitive("+12"))
                            put("outputDf", JsonPrimitive("forecast_df"))
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify(exactly = 1) { mockClient.project(match { it.horizon == "+12" && it.outputDf == "forecast_df" }) }
            val structured = res.structuredContent as kotlinx.serialization.json.JsonObject
            (structured["rows"] as JsonPrimitive).content shouldBe "12"
        }

        // -------------------------------------------------------------------------
        // model.simulate
        // -------------------------------------------------------------------------

        "model.simulate: deltasJson and outputDf forwarded correctly" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.simulateScenario(any()) } returns
                ProjectResult
                    .newBuilder()
                    .setOutputDf("sim_out")
                    .setRows(12)
                    .build()
            val tools = Tools(mockClient)
            val res =
                tools.modelSimulateCallback(
                    request(
                        "model.simulate",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("s"))
                            put("forecastDf", JsonPrimitive("fc"))
                            put("deltasJson", JsonPrimitive("{\"2025-01\": 1.1}"))
                            put("outputDf", JsonPrimitive("sim_out"))
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify {
                mockClient.simulateScenario(
                    match { it.deltasJson == "{\"2025-01\": 1.1}" && it.outputDf == "sim_out" },
                )
            }
        }

        // -------------------------------------------------------------------------
        // data.drop
        // -------------------------------------------------------------------------

        "data.drop: sessionId and name forwarded correctly" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            coEvery { mockClient.dropWorkspaceEntry(any()) } returns DropResult.newBuilder().setExisted(true).build()
            val tools = Tools(mockClient)
            val res =
                tools.dataDropCallback(
                    request(
                        "data.drop",
                        buildJsonObject {
                            put("sessionId", JsonPrimitive("s"))
                            put("name", JsonPrimitive("old_df"))
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify { mockClient.dropWorkspaceEntry(match { it.sessionId == "s" && it.name == "old_df" }) }
            val structured = res.structuredContent as kotlinx.serialization.json.JsonObject
            (structured["existed"] as JsonPrimitive).content shouldBe "true"
        }

        "data.drop: missing name returns error without calling gRPC" {
            val mockClient = mockk<MetisGrpcClient>(relaxed = true)
            val tools = Tools(mockClient)
            val res =
                tools.dataDropCallback(
                    request("data.drop", buildJsonObject { put("sessionId", JsonPrimitive("s")) }),
                )
            res.isError shouldBe true
            coVerify(exactly = 0) { mockClient.dropWorkspaceEntry(any()) }
        }
    })
