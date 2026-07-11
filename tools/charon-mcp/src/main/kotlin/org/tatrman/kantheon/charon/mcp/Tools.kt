package org.tatrman.kantheon.charon.mcp

import com.google.protobuf.InvalidProtocolBufferException
import io.grpc.Metadata
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.transfer.v1.CopyRequest
import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.DbWriteMode
import org.tatrman.transfer.v1.DescribeRequest
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictRequest
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MaterializeRequest
import org.tatrman.transfer.v1.MoveOptions
import org.tatrman.transfer.v1.MoveResult
import org.tatrman.transfer.v1.RedisEntry
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.StageRequest
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.charon.mcp.client.CharonGrpcClient
import org.tatrman.kantheon.common.v1.ResponseMessage

/**
 * The five `move.*` MCP tools — a **zero-logic** wrapper over
 * `org.tatrman.transfer.v1.CharonService` (charon/contracts.md §3): validate JSON
 * → proto, one gRPC call, proto → JSON (incl. the Rule-6 `messages` channel).
 * No move logic, no endpoint knowledge. Locations ride as structured JSON
 * objects (a `kind` discriminator), never stringified (Rule 7 spirit).
 */
class Tools(
    private val charonGrpcClient: CharonGrpcClient? = null,
) {
    private val logger = LoggerFactory.getLogger(Tools::class.java)

    private fun args(request: CallToolRequest): JsonObject? = request.params.arguments

    private fun objArg(
        args: JsonObject?,
        key: String,
    ): JsonObject? = args?.get(key) as? JsonObject

    private fun strField(
        obj: JsonObject?,
        key: String,
    ): String? = (obj?.get(key) as? JsonPrimitive)?.content

    private fun errStructured(
        message: String,
        errorCode: String = "EXECUTION_ERROR",
        extras: JsonObject = buildJsonObject { },
    ) = buildJsonObject {
        put("errorCode", errorCode)
        put("error", message)
        put("message", message)
        put("extras", extras)
    }

    private fun notWiredResult(toolName: String): CallToolResult {
        val msg = "$toolName requires the gRPC charon client; configure charon.host/port to enable."
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "GRPC_NOT_CONFIGURED"),
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

    /** Map a gRPC failure to an MCP error result, passing the Rule-6
     *  `messages` (charon attaches them to the `charon-response-messages-bin`
     *  trailer) through in `extras`. */
    private fun grpcErrorResult(
        toolName: String,
        e: Throwable,
    ): CallToolResult {
        val (status, trailers) =
            when (e) {
                is StatusException -> e.status to e.trailers
                is StatusRuntimeException -> e.status to e.trailers
                else -> null to null
            }
        val code = status?.code?.name ?: "EXECUTION_ERROR"
        val desc = status?.description ?: e.message ?: "charon move failed"
        val messages = trailers?.let { messagesFromTrailers(it) } ?: buildJsonArray { }
        logger.info("{} failed | {} | {}", toolName, code, desc.take(120))
        return CallToolResult(
            content = listOf(TextContent(text = desc)),
            isError = true,
            structuredContent =
                errStructured(
                    desc,
                    errorCode = code,
                    extras = buildJsonObject { put("messages", messages) },
                ),
        )
    }

    private fun ok(structured: JsonObject): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(text = McpJson.encodeToString(structured))),
            structuredContent = structured,
        )

    // -------------------------------------------------------------------------
    // JSON → proto: locations, options, results
    // -------------------------------------------------------------------------

    /** Build a [Location] from a `{kind, …}` JSON object. @throws on bad shape. */
    private fun locationFromJson(obj: JsonObject): Location {
        val kind = strField(obj, "kind") ?: error("location requires a 'kind'")
        val b = Location.newBuilder()
        when (kind) {
            "seaweed" -> {
                val s =
                    SeaweedBlob
                        .newBuilder()
                        .setBucket(strField(obj, "bucket") ?: error("seaweed requires 'bucket'"))
                        .setKey(strField(obj, "key") ?: error("seaweed requires 'key'"))
                strField(obj, "retentionTag")?.let { s.retentionTag = it }
                b.seaweed = s.build()
            }
            "redis" -> {
                val r = RedisEntry.newBuilder().setKey(strField(obj, "key") ?: error("redis requires 'key'"))
                strField(obj, "ttlSeconds")?.let { r.ttlSeconds = parseLong(it, "ttlSeconds") }
                b.redis = r.build()
            }
            "worker_df" -> b.workerDf = workerDfFromJson(obj)
            "db_table" ->
                b.dbTable =
                    DbTable
                        .newBuilder()
                        .setConnectionId(strField(obj, "connectionId") ?: error("db_table requires 'connectionId'"))
                        .setSchema(strField(obj, "schema") ?: error("db_table requires 'schema'"))
                        .setTable(strField(obj, "table") ?: error("db_table requires 'table'"))
                        .build()
            else -> error("unknown location kind '$kind'")
        }
        return b.build()
    }

    private fun workerDfFromJson(obj: JsonObject): WorkerSessionDf =
        WorkerSessionDf
            .newBuilder()
            .setWorkerKind(parseEnum(strField(obj, "workerKind") ?: "POLARS", "workerKind", WorkerKind::valueOf))
            .setSessionId(strField(obj, "sessionId") ?: error("worker_df requires 'sessionId'"))
            .setDfName(strField(obj, "dfName") ?: error("worker_df requires 'dfName'"))
            .build()

    private fun moveOptionsFromJson(obj: JsonObject?): MoveOptions {
        val o = MoveOptions.newBuilder()
        strField(obj, "expectedSchemaFingerprint")?.let { o.expectedSchemaFingerprint = it }
        strField(obj, "dbWriteMode")?.let { o.dbWriteMode = parseEnum(it, "dbWriteMode", DbWriteMode::valueOf) }
        strField(obj, "maxBytes")?.let { o.maxBytes = parseLong(it, "maxBytes") }
        strField(obj, "chunkRows")?.let { o.chunkRows = parseInt(it, "chunkRows") }
        return o.build()
    }

    /** Parse an enum field, reporting an unknown value as a BAD_REQUEST
     *  (`IllegalStateException`, the type the callbacks catch) rather than
     *  letting `valueOf`'s `IllegalArgumentException` escape to a generic error. */
    private fun <E> parseEnum(
        value: String,
        field: String,
        of: (String) -> E,
    ): E =
        try {
            of(value)
        } catch (e: IllegalArgumentException) {
            error("invalid '$field' value '$value'")
        }

    /** Parse a present numeric field, rejecting an unparseable value (rather
     *  than silently dropping it — a dropped `maxBytes` cap is a footgun). */
    private fun parseLong(
        value: String,
        field: String,
    ): Long = value.toLongOrNull() ?: error("'$field' must be an integer, got '$value'")

    private fun parseInt(
        value: String,
        field: String,
    ): Int = value.toIntOrNull() ?: error("'$field' must be an integer, got '$value'")

    private fun messagesFromProto(messages: List<ResponseMessage>) =
        buildJsonArray {
            messages.forEach { m ->
                add(
                    buildJsonObject {
                        put("severity", JsonPrimitive(m.severity.name))
                        put("code", JsonPrimitive(m.code))
                        put("humanMessage", JsonPrimitive(m.humanMessage))
                    },
                )
            }
        }

    private fun messagesFromTrailers(trailers: Metadata) =
        buildJsonArray {
            val all = trailers.getAll(RESPONSE_MESSAGES_TRAILER_KEY) ?: return@buildJsonArray
            all.forEach { bytes ->
                try {
                    val m = ResponseMessage.parseFrom(bytes)
                    add(
                        buildJsonObject {
                            put("severity", JsonPrimitive(m.severity.name))
                            put("code", JsonPrimitive(m.code))
                            put("humanMessage", JsonPrimitive(m.humanMessage))
                        },
                    )
                } catch (e: InvalidProtocolBufferException) {
                    logger.debug("could not parse a response-message trailer: {}", e.message)
                }
            }
        }

    private fun moveResultJson(r: MoveResult): JsonObject =
        buildJsonObject {
            put("schemaFingerprint", JsonPrimitive(r.schemaFingerprint))
            put("schemaJson", JsonPrimitive(r.schemaJson))
            put("rowCount", JsonPrimitive(r.rowCount))
            put("sizeBytes", JsonPrimitive(r.sizeBytes))
            put("durationMs", JsonPrimitive(r.durationMs))
            put("messages", messagesFromProto(r.messagesList))
        }

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    private fun locationSchemaProp(
        b: kotlinx.serialization.json.JsonObjectBuilder,
        name: String,
        desc: String,
    ) = b.putJsonObject(name) {
        put("type", "object")
        put("description", desc)
    }

    val materializeTool =
        Tool(
            name = "move.materialize",
            description = "Materialize a source Location into a seaweed | redis | db_table target.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            locationSchemaProp(this, "source", "Source location. $LOCATION_KINDS_DOC")
                            locationSchemaProp(
                                this,
                                "target",
                                "Target location (seaweed|redis|db_table). $LOCATION_KINDS_DOC",
                            )
                            locationSchemaProp(this, "options", OPTIONS_DOC)
                        },
                    required = listOf("source", "target"),
                ),
        )

    val stageTool =
        Tool(
            name = "move.stage",
            description = "Stage a source Location INTO a worker session DataFrame (target = worker_df).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            locationSchemaProp(this, "source", "Source location. $LOCATION_KINDS_DOC")
                            locationSchemaProp(
                                this,
                                "target",
                                "Worker target { workerKind=POLARS|METIS, sessionId, dfName }.",
                            )
                            locationSchemaProp(this, "options", OPTIONS_DOC)
                        },
                    required = listOf("source", "target"),
                ),
        )

    val copyTool =
        Tool(
            name = "move.copy",
            description = "Copy a source Location to any legal target (the generic move verb).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            locationSchemaProp(this, "source", "Source location. $LOCATION_KINDS_DOC")
                            locationSchemaProp(this, "target", "Target location. $LOCATION_KINDS_DOC")
                            locationSchemaProp(this, "options", OPTIONS_DOC)
                        },
                    required = listOf("source", "target"),
                ),
        )

    val evictTool =
        Tool(
            name = "move.evict",
            description = "Evict (delete) a blob / redis / worker_df location (db_table not allowed).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            locationSchemaProp(
                                this,
                                "location",
                                "Location to evict (db_table not allowed). $LOCATION_KINDS_DOC",
                            )
                        },
                    required = listOf("location"),
                ),
        )

    val describeTool =
        Tool(
            name = "move.describe",
            description = "Describe a Location: exists, schema fingerprint, row count, expiry (PD-5 liveness).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            locationSchemaProp(this, "location", "Location to describe. $LOCATION_KINDS_DOC")
                        },
                    required = listOf("location"),
                ),
        )

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    suspend fun materializeCallback(request: CallToolRequest): CallToolResult {
        val grpc = charonGrpcClient ?: return notWiredResult("move.materialize")
        val a = args(request)
        val source = objArg(a, "source") ?: return missingArgResult("move.materialize", "source")
        val target = objArg(a, "target") ?: return missingArgResult("move.materialize", "target")
        return try {
            val req =
                MaterializeRequest
                    .newBuilder()
                    .setSource(locationFromJson(source))
                    .setTarget(locationFromJson(target))
                    .setOptions(moveOptionsFromJson(objArg(a, "options")))
                    .build()
            ok(moveResultJson(grpc.materialize(req)))
        } catch (e: StatusException) {
            grpcErrorResult("move.materialize", e)
        } catch (e: StatusRuntimeException) {
            grpcErrorResult("move.materialize", e)
        } catch (e: IllegalStateException) {
            missingOrBadShape("move.materialize", e)
        }
    }

    suspend fun stageCallback(request: CallToolRequest): CallToolResult {
        val grpc = charonGrpcClient ?: return notWiredResult("move.stage")
        val a = args(request)
        val source = objArg(a, "source") ?: return missingArgResult("move.stage", "source")
        val target = objArg(a, "target") ?: return missingArgResult("move.stage", "target")
        return try {
            val req =
                StageRequest
                    .newBuilder()
                    .setSource(locationFromJson(source))
                    .setTarget(workerDfFromJson(target))
                    .setOptions(moveOptionsFromJson(objArg(a, "options")))
                    .build()
            ok(moveResultJson(grpc.stage(req)))
        } catch (e: StatusException) {
            grpcErrorResult("move.stage", e)
        } catch (e: StatusRuntimeException) {
            grpcErrorResult("move.stage", e)
        } catch (e: IllegalStateException) {
            missingOrBadShape("move.stage", e)
        }
    }

    suspend fun copyCallback(request: CallToolRequest): CallToolResult {
        val grpc = charonGrpcClient ?: return notWiredResult("move.copy")
        val a = args(request)
        val source = objArg(a, "source") ?: return missingArgResult("move.copy", "source")
        val target = objArg(a, "target") ?: return missingArgResult("move.copy", "target")
        return try {
            val req =
                CopyRequest
                    .newBuilder()
                    .setSource(locationFromJson(source))
                    .setTarget(locationFromJson(target))
                    .setOptions(moveOptionsFromJson(objArg(a, "options")))
                    .build()
            ok(moveResultJson(grpc.copy(req)))
        } catch (e: StatusException) {
            grpcErrorResult("move.copy", e)
        } catch (e: StatusRuntimeException) {
            grpcErrorResult("move.copy", e)
        } catch (e: IllegalStateException) {
            missingOrBadShape("move.copy", e)
        }
    }

    suspend fun evictCallback(request: CallToolRequest): CallToolResult {
        val grpc = charonGrpcClient ?: return notWiredResult("move.evict")
        val a = args(request)
        val location = objArg(a, "location") ?: return missingArgResult("move.evict", "location")
        return try {
            val result: EvictResult =
                grpc.evict(
                    EvictRequest.newBuilder().setLocation(locationFromJson(location)).build(),
                )
            ok(
                buildJsonObject {
                    put("existed", JsonPrimitive(result.existed))
                    put("messages", messagesFromProto(result.messagesList))
                },
            )
        } catch (e: StatusException) {
            grpcErrorResult("move.evict", e)
        } catch (e: StatusRuntimeException) {
            grpcErrorResult("move.evict", e)
        } catch (e: IllegalStateException) {
            missingOrBadShape("move.evict", e)
        }
    }

    suspend fun describeCallback(request: CallToolRequest): CallToolResult {
        val grpc = charonGrpcClient ?: return notWiredResult("move.describe")
        val a = args(request)
        val location = objArg(a, "location") ?: return missingArgResult("move.describe", "location")
        return try {
            val r: DescribeResult =
                grpc.describe(
                    DescribeRequest.newBuilder().setLocation(locationFromJson(location)).build(),
                )
            ok(
                buildJsonObject {
                    put("exists", JsonPrimitive(r.exists))
                    put("schemaFingerprint", JsonPrimitive(r.schemaFingerprint))
                    put("schemaJson", JsonPrimitive(r.schemaJson))
                    put("rowCount", JsonPrimitive(r.rowCount))
                    put("rowCountExact", JsonPrimitive(r.rowCountExact))
                    put("sizeBytes", JsonPrimitive(r.sizeBytes))
                    put("expiresAt", JsonPrimitive(r.expiresAt))
                    put("messages", messagesFromProto(r.messagesList))
                },
            )
        } catch (e: StatusException) {
            grpcErrorResult("move.describe", e)
        } catch (e: StatusRuntimeException) {
            grpcErrorResult("move.describe", e)
        } catch (e: IllegalStateException) {
            missingOrBadShape("move.describe", e)
        }
    }

    private fun missingOrBadShape(
        toolName: String,
        e: IllegalStateException,
    ): CallToolResult {
        val msg = e.message ?: "bad request shape"
        return CallToolResult(
            content = listOf(TextContent(text = msg)),
            isError = true,
            structuredContent = errStructured(msg, errorCode = "BAD_REQUEST"),
        )
    }

    companion object {
        /** Mirror of the charon server's Rule-6 trailer key
         *  (`CharonServiceImpl.RESPONSE_MESSAGES_TRAILER_KEY`). */
        val RESPONSE_MESSAGES_TRAILER_KEY: Metadata.Key<ByteArray> =
            Metadata.Key.of("charon-response-messages-bin", Metadata.BINARY_BYTE_MARSHALLER)

        /** Discriminated-union reference for a `Location` object, appended to
         *  the location field descriptions so an LLM caller knows the `kind`
         *  values and each shape's required fields without reading the proto. */
        const val LOCATION_KINDS_DOC: String =
            "Discriminated by 'kind': " +
                "seaweed{bucket,key,retentionTag?} | " +
                "redis{key,ttlSeconds?} | " +
                "worker_df{workerKind=POLARS|METIS,sessionId,dfName} | " +
                "db_table{connectionId,schema,table}."

        /** MoveOptions reference for the options field. */
        const val OPTIONS_DOC: String =
            "Optional MoveOptions{dbWriteMode=CREATE|REPLACE|APPEND (required for db_table targets), " +
                "expectedSchemaFingerprint?, maxBytes?, chunkRows?}."
    }
}
