package org.tatrman.kantheon.pythia.handles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.pythia.v1.DbTableRef
import org.tatrman.kantheon.pythia.v1.Handle
import org.tatrman.kantheon.pythia.v1.LiveQueryRef
import org.tatrman.kantheon.pythia.v1.PgResultSnapshot
import org.tatrman.kantheon.pythia.v1.RedisArrowEntry
import org.tatrman.kantheon.pythia.v1.SeaweedArrowBlob
import org.tatrman.kantheon.pythia.v1.WorkerSessionDF
import java.util.concurrent.ConcurrentHashMap

/** The result of inlining a query result as a [PgResultSnapshot] (divergence 1). */
data class SnapshotResult(
    val handle: Handle,
    val truncated: Boolean,
)

/**
 * Handle table v0 (architecture §5): `LiveQueryRef` + `PgResultSnapshot` (small
 * results inlined as Arrow IPC bytes, capped by `pythia.handles.inline-max-bytes`).
 * It also keeps an in-memory **projection cache** of result rows so a downstream
 * node's `params_json` can bind to an upstream handle's column (`HandleRef`). The
 * Phase-4 handle kinds (worker_df / seaweed / redis / db_table) attach later.
 */
class HandleTable(
    private val inlineMaxBytes: Long = 1_048_576,
) {
    private val handles = ConcurrentHashMap<String, Handle>()
    private val projections = ConcurrentHashMap<String, JsonArray>()
    private val renderedBlocks = java.util.concurrent.CopyOnWriteArrayList<org.tatrman.kantheon.envelope.v1.Block>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Stash a Block produced by a RenderNode (consumed by the synthesizer). */
    fun putBlock(block: org.tatrman.kantheon.envelope.v1.Block) {
        renderedBlocks += block
    }

    /** The render-produced blocks, in production order. */
    fun blocks(): List<org.tatrman.kantheon.envelope.v1.Block> = renderedBlocks.toList()

    fun put(handle: Handle) {
        handles[handle.handleId] = handle
    }

    fun get(handleId: String): Handle? = handles[handleId]

    fun rows(handleId: String): JsonArray? = projections[handleId]

    /** Register a not-yet-materialised query reference. */
    fun putLiveQuery(
        handleId: String,
        queryRef: String,
        argsJson: String,
    ): Handle {
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setLiveQuery(LiveQueryRef.newBuilder().setQueryRef(queryRef).setArgsJson(argsJson))
                .build()
        put(handle)
        return handle
    }

    /**
     * Register a [WorkerSessionDF] handle (Phase 4) — a DataFrame live in a Polars/
     * Metis worker session, keyed `(session_id, df_name)` (divergence 5). When [rows]
     * is supplied (mocked path) they are cached for downstream `HandleRef` binding and
     * evidence comparison; the live path leaves rows in the worker (scan-on-demand).
     */
    fun putWorkerDf(
        handleId: String,
        workerPod: String,
        sessionId: String,
        dfName: String,
        rowCountEst: Long = 0,
        schemaJson: String = "",
        rows: JsonArray? = null,
    ): Handle {
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setWorkerDf(
                    WorkerSessionDF
                        .newBuilder()
                        .setWorkerPod(workerPod)
                        .setSessionId(sessionId)
                        .setDfName(dfName)
                        .setRowCountEst(rowCountEst)
                        .setSchemaJson(schemaJson),
                ).build()
        rows?.let { projections[handleId] = it }
        put(handle)
        return handle
    }

    /** Register a [SeaweedArrowBlob] handle (an evidence blob in `pythia-evidence`). */
    fun putSeaweed(
        handleId: String,
        url: String,
        rowCount: Long = 0,
        contentHash: String = "",
        retentionUntil: String? = null,
    ): Handle {
        val builder =
            SeaweedArrowBlob
                .newBuilder()
                .setUrl(url)
                .setRowCount(rowCount)
                .setContentHash(contentHash)
        retentionUntil?.let { builder.retentionUntil = it }
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setSeaweed(builder)
                .build()
        put(handle)
        return handle
    }

    /** Register a [RedisArrowEntry] handle. */
    fun putRedis(
        handleId: String,
        key: String,
        ttl: String = "",
    ): Handle {
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setRedis(RedisArrowEntry.newBuilder().setKey(key).setTtl(ttl))
                .build()
        put(handle)
        return handle
    }

    /** Register a [DbTableRef] handle (a Charon named-connection table). */
    fun putDbTable(
        handleId: String,
        connection: String,
        table: String,
        schemaJson: String = "",
    ): Handle {
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setDbTable(
                    DbTableRef
                        .newBuilder()
                        .setConnection(connection)
                        .setTable(table)
                        .setSchemaJson(schemaJson),
                ).build()
        put(handle)
        return handle
    }

    /**
     * Inline a small result as a [PgResultSnapshot]. The rows are kept (as JSON) in
     * the projection cache for `HandleRef` binding; the proto snapshot carries the
     * Arrow-equivalent bytes capped at [inlineMaxBytes] — oversize → `truncated`
     * (the caller flags it for the Phase-4 materialise path).
     */
    fun putSnapshot(
        handleId: String,
        rows: JsonArray,
    ): SnapshotResult {
        val bytes = rows.toString().toByteArray()
        val truncated = bytes.size > inlineMaxBytes
        projections[handleId] = rows
        val snapshot =
            PgResultSnapshot
                .newBuilder()
                .setRowCount(rows.size.toLong())
                .setTruncated(truncated)
                .apply {
                    if (!truncated) {
                        setArrowIpc(
                            com.google.protobuf.ByteString
                                .copyFrom(bytes),
                        )
                    }
                }.build()
        val handle =
            Handle
                .newBuilder()
                .setHandleId(handleId)
                .setPgSnapshot(snapshot)
                .build()
        put(handle)
        return SnapshotResult(handle, truncated)
    }

    /**
     * Resolve `HandleRef` projections in a node's `params_json` (Rule 7). A param
     * value of the form `{ "$handleRef": { "handle": "h1", "projection": "customer_id" } }`
     * is replaced with the JSON array of that column's values from the referenced
     * handle's cached rows. Unknown handles/projections resolve to an empty array.
     */
    fun resolveBindings(paramsJson: String): String {
        val root = runCatching { json.parseToJsonElement(paramsJson) }.getOrNull() ?: return paramsJson
        return resolve(root).toString()
    }

    private fun resolve(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                val ref = element["\$handleRef"]
                if (ref is JsonObject) {
                    projectColumn(ref)
                } else {
                    JsonObject(element.mapValues { resolve(it.value) })
                }
            }
            is JsonArray -> JsonArray(element.map { resolve(it) })
            else -> element
        }

    /** A `$handleRef` found in a node's params, with the size of the column it projects. */
    data class HandleRefSpec(
        val handle: String,
        val projection: String,
        val size: Int,
    )

    /** Every `$handleRef` in [paramsJson], with the projected column's current size (for the IN-list cap check). */
    fun handleRefs(paramsJson: String): List<HandleRefSpec> {
        val root = runCatching { json.parseToJsonElement(paramsJson) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<HandleRefSpec>()

        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    val ref = el["\$handleRef"]
                    if (ref is JsonObject) {
                        val handle = (ref["handle"] as? JsonPrimitive)?.content
                        val projection = (ref["projection"] as? JsonPrimitive)?.content
                        if (handle != null && projection != null) {
                            out += HandleRefSpec(handle, projection, projectColumn(ref).size)
                        }
                    } else {
                        el.values.forEach(::walk)
                    }
                }
                is JsonArray -> el.forEach(::walk)
                else -> {}
            }
        }
        walk(root)
        return out
    }

    /** Replace the `$handleRef` to ([handle], [projection]) with a `$dfRef` marker to a staged worker DF. */
    fun replaceWithDfRef(
        paramsJson: String,
        handle: String,
        projection: String,
        stagedHandleId: String,
    ): String {
        val root = runCatching { json.parseToJsonElement(paramsJson) }.getOrNull() ?: return paramsJson

        fun rewrite(el: JsonElement): JsonElement =
            when (el) {
                is JsonObject -> {
                    val ref = el["\$handleRef"] as? JsonObject
                    val h = (ref?.get("handle") as? JsonPrimitive)?.content
                    val p = (ref?.get("projection") as? JsonPrimitive)?.content
                    if (h == handle && p == projection) {
                        JsonObject(mapOf("\$dfRef" to JsonPrimitive(stagedHandleId)))
                    } else {
                        JsonObject(el.mapValues { rewrite(it.value) })
                    }
                }
                is JsonArray -> JsonArray(el.map { rewrite(it) })
                else -> el
            }
        return rewrite(root).toString()
    }

    private fun projectColumn(ref: JsonObject): JsonArray {
        val handleId = (ref["handle"] as? JsonPrimitive)?.content ?: return JsonArray(emptyList())
        val projection = (ref["projection"] as? JsonPrimitive)?.content ?: return JsonArray(emptyList())
        val rows = projections[handleId] ?: return JsonArray(emptyList())
        return buildJsonArray {
            rows.forEach { row -> (row.jsonObject[projection])?.let { add(it) } }
        }
    }
}
