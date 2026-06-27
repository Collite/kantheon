package org.tatrman.kantheon.midas.loaders.excel.store

import org.tatrman.kantheon.midas.v1.LoaderRun
import java.util.concurrent.ConcurrentHashMap

/** A loader run plus the blob key the proto does not carry. */
data class StoredRun(
    val run: LoaderRun,
    val blobRef: String,
)

/**
 * Persists loader runs (Stage 1.5 T6). `loader_runs` is loader-owned state (the
 * proto's metadata + the blob ref). Lookups are tenant-scoped. Idempotency: an
 * upload whose `blobRef` already has a run returns that run.
 *
 * > **v1 follow-up:** this is an in-memory store; the DB-backed implementation over
 * > the `loader_runs` table (V0001, RLS) is a clean swap behind this interface and
 * > is tracked in the Stage 1.5 task list (it needs the loader's own tenant-pinned
 * > DB connection). In-memory is correct for a single local replica.
 */
interface LoaderRunStore {
    fun save(stored: StoredRun)

    fun get(
        tenantId: String,
        runId: String,
    ): StoredRun?

    fun findByBlobRef(
        tenantId: String,
        blobRef: String,
    ): StoredRun?

    fun list(
        tenantId: String,
        portfolioId: String?,
    ): List<StoredRun>
}

class InMemoryLoaderRunStore : LoaderRunStore {
    private val byId = ConcurrentHashMap<String, StoredRun>()

    private fun key(
        tenantId: String,
        runId: String,
    ) = "$tenantId/$runId"

    override fun save(stored: StoredRun) {
        byId[key(stored.run.tenantId, stored.run.loaderRunId)] = stored
    }

    override fun get(
        tenantId: String,
        runId: String,
    ): StoredRun? = byId[key(tenantId, runId)]

    override fun findByBlobRef(
        tenantId: String,
        blobRef: String,
    ): StoredRun? = byId.values.firstOrNull { it.run.tenantId == tenantId && it.blobRef == blobRef }

    override fun list(
        tenantId: String,
        portfolioId: String?,
    ): List<StoredRun> =
        byId.values
            .filter { it.run.tenantId == tenantId && (portfolioId == null || it.run.portfolioId == portfolioId) }
            .sortedByDescending { it.run.uploadedAt.seconds }
}
