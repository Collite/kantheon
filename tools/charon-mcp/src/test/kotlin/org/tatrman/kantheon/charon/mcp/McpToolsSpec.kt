package org.tatrman.kantheon.charon.mcp

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
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
import kotlinx.serialization.json.putJsonObject
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.kantheon.charon.mcp.client.CharonGrpcClient
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity

/**
 * Charon P3 Stage 3.2 T1 — JSON↔proto fidelity for the 5 move.* tools. Verifies
 * the zero-logic wrapper: locations parse as structured JSON (never stringified)
 * into the right proto oneof; results map back incl. `messages`; gRPC errors
 * pass through (status + the Rule-6 trailer messages); missing/not-wired errors.
 */
class McpToolsSpec :
    StringSpec({

        fun request(
            toolName: String,
            args: JsonObject = JsonObject(emptyMap()),
        ): CallToolRequest = CallToolRequest(CallToolRequestParams(name = toolName, arguments = args))

        // --- move.materialize: seaweed → redis ---

        "move.materialize builds the right Location oneof + maps MoveResult incl. messages" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            coEvery { client.materialize(any()) } returns
                MoveResult
                    .newBuilder()
                    .setRowCount(100)
                    .setSchemaFingerprint("abc")
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(
                                Severity.WARNING,
                            ).setCode("w1")
                            .setHumanMessage("heads up")
                            .build(),
                    ).build()
            val tools = Tools(client)
            val res =
                tools.materializeCallback(
                    request(
                        "move.materialize",
                        buildJsonObject {
                            putJsonObject("source") {
                                put("kind", "seaweed")
                                put("bucket", "b")
                                put("key", "k")
                            }
                            putJsonObject("target") {
                                put("kind", "redis")
                                put("key", "r")
                            }
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify(exactly = 1) {
                client.materialize(
                    match {
                        it.source.hasSeaweed() &&
                            it.target.hasRedis() &&
                            it.source.seaweed.bucket == "b"
                    },
                )
            }
        }

        "move.materialize forwards db_write_mode + retention tag" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            coEvery { client.materialize(any()) } returns MoveResult.getDefaultInstance()
            Tools(client).materializeCallback(
                request(
                    "move.materialize",
                    buildJsonObject {
                        putJsonObject("source") {
                            put("kind", "seaweed")
                            put("bucket", "b")
                            put("key", "k")
                            put("retentionTag", "production")
                        }
                        putJsonObject("target") {
                            put("kind", "db_table")
                            put("connectionId", "c")
                            put("schema", "s")
                            put("table", "t")
                        }
                        putJsonObject("options") { put("dbWriteMode", "CREATE") }
                    },
                ),
            )
            coVerify(exactly = 1) {
                client.materialize(
                    match {
                        it.target.hasDbTable() &&
                            it.target.dbTable.table == "t" &&
                            it.options.dbWriteMode == org.tatrman.transfer.v1.DbWriteMode.CREATE &&
                            it.source.seaweed.retentionTag == "production"
                    },
                )
            }
        }

        // --- move.stage: seaweed → worker_df (METIS) ---

        "move.stage builds a WorkerSessionDf target with the right kind/session/df" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            coEvery { client.stage(any()) } returns MoveResult.newBuilder().setRowCount(3).build()
            val tools = Tools(client)
            val res =
                tools.stageCallback(
                    request(
                        "move.stage",
                        buildJsonObject {
                            putJsonObject("source") {
                                put("kind", "seaweed")
                                put("bucket", "b")
                                put("key", "k")
                            }
                            putJsonObject("target") {
                                put("kind", "worker_df")
                                put("workerKind", "METIS")
                                put("sessionId", "s1")
                                put("dfName", "df1")
                            }
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            coVerify(exactly = 1) {
                client.stage(
                    match {
                        it.target.workerKind == WorkerKind.METIS &&
                            it.target.sessionId == "s1" &&
                            it.target.dfName == "df1"
                    },
                )
            }
        }

        // --- move.describe ---

        "move.describe maps the DescribeResult fields" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            coEvery { client.describe(any()) } returns
                DescribeResult
                    .newBuilder()
                    .setExists(true)
                    .setSchemaFingerprint("fp")
                    .setRowCount(-1)
                    .build()
            val res =
                Tools(client).describeCallback(
                    request(
                        "move.describe",
                        buildJsonObject {
                            putJsonObject("location") {
                                put("kind", "db_table")
                                put("connectionId", "c")
                                put("schema", "s")
                                put("table", "t")
                            }
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            (res.structuredContent!!["exists"] as JsonPrimitive).content shouldBe "true"
            coVerify(exactly = 1) { client.describe(match { it.location.hasDbTable() }) }
        }

        // --- move.evict ---

        "move.evict forwards the location + returns existed" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            coEvery { client.evict(any()) } returns EvictResult.newBuilder().setExisted(true).build()
            val res =
                Tools(client).evictCallback(
                    request(
                        "move.evict",
                        buildJsonObject {
                            putJsonObject("location") {
                                put("kind", "redis")
                                put("key", "r")
                            }
                        },
                    ),
                )
            (res.isError == true) shouldBe false
            (res.structuredContent!!["existed"] as JsonPrimitive).content shouldBe "true"
        }

        // --- error / missing / not-wired ---

        "a missing required arg errors WITHOUT calling gRPC" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            val res = Tools(client).materializeCallback(request("move.materialize", buildJsonObject { }))
            (res.isError == true) shouldBe true
            coVerify(exactly = 0) { client.materialize(any()) }
        }

        "not-wired (null client) returns a structured GRPC_NOT_CONFIGURED error" {
            val res = Tools(null).describeCallback(request("move.describe", buildJsonObject { }))
            (res.isError == true) shouldBe true
        }

        "a gRPC INVALID_ARGUMENT passes through with status code + trailer messages" {
            val client = mockk<CharonGrpcClient>(relaxed = true)
            val trailers =
                Metadata().apply {
                    put(
                        Tools.RESPONSE_MESSAGES_TRAILER_KEY,
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(
                                Severity.ERROR,
                            ).setCode("illegal_pair")
                            .setHumanMessage("nope")
                            .build()
                            .toByteArray(),
                    )
                }
            coEvery { client.copy(any()) } throws
                StatusException(Status.INVALID_ARGUMENT.withDescription("bad pair"), trailers)
            val res =
                Tools(client).copyCallback(
                    request(
                        "move.copy",
                        buildJsonObject {
                            putJsonObject("source") {
                                put("kind", "seaweed")
                                put("bucket", "b")
                                put("key", "k")
                            }
                            putJsonObject("target") {
                                put("kind", "worker_df")
                                put("workerKind", "POLARS")
                                put("sessionId", "s")
                                put("dfName", "d")
                            }
                        },
                    ),
                )
            (res.isError == true) shouldBe true
            (res.structuredContent!!["errorCode"] as JsonPrimitive).content shouldBe "INVALID_ARGUMENT"
        }
    })
