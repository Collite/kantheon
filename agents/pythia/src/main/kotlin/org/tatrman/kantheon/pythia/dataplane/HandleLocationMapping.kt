package org.tatrman.kantheon.pythia.dataplane

import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.RedisEntry
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.pythia.v1.Handle

/**
 * Handle ↔ Charon `Location` mapping (contracts §6, charon/contracts.md §7).
 *
 * The four Charon-backed Pythia handle kinds map onto Charon's `Location` union:
 *   `SeaweedArrowBlob → SeaweedBlob`, `RedisArrowEntry → RedisEntry`,
 *   `WorkerSessionDF → WorkerSessionDf`, `DbTableRef → DbTable`.
 *
 * `LiveQueryRef` and `PgResultSnapshot` are **Pythia-internal** (Pythia's PG is
 * never a Charon connection) — they have no mapping and must never be sent to
 * Charon; [toLocation] throws on them so a wiring bug fails loud, not silently.
 */
object HandleLocationMapping {
    /**
     * The Charon `Location` for a Charon-backed handle. [workerKind] disambiguates
     * a `WorkerSessionDF` between the Polars worker (default) and a Metis workspace
     * (Stage 4.2 staging) — the Pythia handle carries `worker_pod`/`session_id`/
     * `df_name` but no engine discriminator.
     */
    fun toLocation(
        handle: Handle,
        workerKind: WorkerKind = WorkerKind.POLARS,
    ): Location =
        when (handle.kindCase) {
            Handle.KindCase.SEAWEED -> {
                val (bucket, key) = splitSeaweedUrl(handle.seaweed.url)
                Location
                    .newBuilder()
                    .setSeaweed(SeaweedBlob.newBuilder().setBucket(bucket).setKey(key))
                    .build()
            }
            Handle.KindCase.REDIS ->
                Location.newBuilder().setRedis(RedisEntry.newBuilder().setKey(handle.redis.key)).build()
            Handle.KindCase.WORKER_DF ->
                Location
                    .newBuilder()
                    .setWorkerDf(
                        WorkerSessionDf
                            .newBuilder()
                            .setWorkerKind(workerKind)
                            .setSessionId(handle.workerDf.sessionId)
                            .setDfName(handle.workerDf.dfName),
                    ).build()
            Handle.KindCase.DB_TABLE -> {
                val (schema, table) = splitTable(handle.dbTable.table)
                Location
                    .newBuilder()
                    .setDbTable(
                        DbTable
                            .newBuilder()
                            .setConnectionId(handle.dbTable.connection)
                            .setSchema(schema)
                            .setTable(table),
                    ).build()
            }
            Handle.KindCase.LIVE_QUERY, Handle.KindCase.PG_SNAPSHOT ->
                throw IllegalArgumentException(
                    "handle '${handle.handleId}' kind ${handle.kindCase} is Pythia-internal — never sent to Charon",
                )
            else ->
                throw IllegalArgumentException("handle '${handle.handleId}' has no kind set")
        }

    /** Parse a Seaweed url (`s3://bucket/key`, `bucket/key`, or bare `key`) into (bucket, key). */
    private fun splitSeaweedUrl(url: String): Pair<String, String> {
        val stripped = url.removePrefix("s3://").removePrefix("seaweed://").trimStart('/')
        val slash = stripped.indexOf('/')
        return if (slash < 0) "" to stripped else stripped.substring(0, slash) to stripped.substring(slash + 1)
    }

    /** Split a `schema.table` (or bare `table`) into (schema, table). */
    private fun splitTable(qualified: String): Pair<String, String> {
        val dot = qualified.lastIndexOf('.')
        return if (dot < 0) "" to qualified else qualified.substring(0, dot) to qualified.substring(dot + 1)
    }
}
