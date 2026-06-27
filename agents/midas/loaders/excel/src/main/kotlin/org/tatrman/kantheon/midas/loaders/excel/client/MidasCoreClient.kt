package org.tatrman.kantheon.midas.loaders.excel.client

import org.tatrman.kantheon.midas.v1.Transaction

/** The forwarded caller identity for an on-behalf-of call to Midas-core. */
data class CallContext(
    val bearer: String,
    val tenantId: String,
    val userId: String,
)

/** Outcome of a proxied batch insert (mirrors `BatchInsertTransactionsResponse`). */
data class BatchResult(
    val inserted: Int,
    val skipped: Int,
    val failed: Int,
)

/**
 * The loader's view of Midas-core (Stage 1.5 T6). The loader owns `loader_runs` +
 * the uploaded blobs, but every *transaction* and *asset* write goes through
 * Midas-core's REST API under the caller's bearer (OBO) — never a direct DB write —
 * so RLS + the assets `WITH CHECK` stay the single enforcement point. An interface
 * so the lifecycle is unit-testable without a live Midas-core.
 */
interface MidasCoreClient {
    /** Resolve a tradeable symbol to an `asset_id`, creating the asset if absent. */
    suspend fun resolveAsset(
        symbol: String,
        currency: String,
        ctx: CallContext,
    ): String

    /** The set of `external_id`s already recorded for a portfolio (for preview dedup). */
    suspend fun existingExternalIds(
        portfolioId: String,
        ctx: CallContext,
    ): Set<String>

    /** Commit drafts via `POST /transactions:batch` (`skip_existing` = idempotent re-commit). */
    suspend fun batchInsert(
        transactions: List<Transaction>,
        skipExisting: Boolean,
        ctx: CallContext,
    ): BatchResult
}
