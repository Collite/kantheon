package org.tatrman.kantheon.midas.loaders.excel.service

import com.google.protobuf.Timestamp
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.excel.client.BatchResult
import org.tatrman.kantheon.midas.loaders.excel.client.CallContext
import org.tatrman.kantheon.midas.loaders.excel.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.excel.mapper.DraftRow
import org.tatrman.kantheon.midas.loaders.excel.mapper.TransactionMapper
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerRegistry
import org.tatrman.kantheon.midas.loaders.excel.parser.ExcelParseException
import org.tatrman.kantheon.midas.loaders.excel.parser.ExcelParser
import org.tatrman.kantheon.midas.loaders.excel.storage.BlobStore
import org.tatrman.kantheon.midas.loaders.excel.store.LoaderRunStore
import org.tatrman.kantheon.midas.loaders.excel.store.StoredRun
import org.tatrman.kantheon.midas.v1.LoaderPreview
import org.tatrman.kantheon.midas.v1.LoaderRun
import org.tatrman.kantheon.midas.v1.LoaderRunStatus
import org.tatrman.kantheon.midas.v1.PreviewDecision
import org.tatrman.kantheon.midas.v1.PreviewRow
import org.tatrman.kantheon.midas.v1.PreviewSummary
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Thrown when a `runId` is not found in the caller's tenant scope (→ 404). */
class LoaderRunNotFoundException(
    runId: String,
) : NoSuchElementException("loader run '$runId' not found")

/**
 * The Excel-loader lifecycle (Stage 1.5 T6): upload → preview → commit, over the
 * parser/mapper (Stage 1.5 T1–T4), the [LoaderRunStore] (loader-owned metadata), the
 * [BlobStore] (the re-parsed source of truth), and [MidasCoreClient] (asset
 * resolution + the batch commit, under the caller's bearer).
 *
 * **Idempotency.** The blob key is `tenant/portfolio/broker/<sha256>` — a
 * byte-identical re-upload resolves to the same key, so `upload` returns the existing
 * run; and `commit` passes `skip_existing` so Midas-core skips already-recorded
 * `external_id`s (the loader stamps `<broker>:<ref>`). Re-committing inserts nothing new.
 */
class LoaderService(
    private val registry: BrokerRegistry,
    private val client: MidasCoreClient,
    private val store: LoaderRunStore,
    private val blobStore: BlobStore,
    private val parser: ExcelParser = ExcelParser(),
    private val mapper: TransactionMapper = TransactionMapper(),
    private val clock: () -> Instant = Instant::now,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val log = LoggerFactory.getLogger(LoaderService::class.java)

    /** Parse-validate + persist an upload; returns the existing run for a re-upload of the same bytes. */
    fun upload(
        bytes: ByteArray,
        brokerId: String,
        portfolioId: String,
        ctx: CallContext,
    ): LoaderRun {
        val template = registry.get(brokerId) // throws UnknownBrokerException (→ 400)
        val blobRef = "${ctx.tenantId}/$portfolioId/$brokerId/${sha256(bytes)}.xlsx"
        store.findByBlobRef(ctx.tenantId, blobRef)?.let {
            log.info("re-upload of {} → existing run {}", blobRef, it.run.loaderRunId)
            return it.run
        }

        val (status, total, error) =
            try {
                Triple(LoaderRunStatus.LR_PREVIEW_READY, parser.parse(bytes.inputStream(), template).size, "")
            } catch (e: ExcelParseException) {
                Triple(LoaderRunStatus.LR_FAILED, 0, e.message.orEmpty())
            }

        blobStore.put(blobRef, bytes)
        val run =
            LoaderRun
                .newBuilder()
                .setLoaderRunId(idGen())
                .setSourceKind("EXCEL")
                .setBrokerId(brokerId)
                .setPortfolioId(portfolioId)
                .setTenantId(ctx.tenantId)
                .setUserId(ctx.userId)
                .setStatus(status)
                .setUploadedAt(now())
                .setRowCountTotal(total)
                .setErrorSummary(error)
                .build()
        store.save(StoredRun(run, blobRef))
        return run
    }

    fun getRun(
        runId: String,
        ctx: CallContext,
    ): LoaderRun = stored(runId, ctx).run

    fun listRuns(
        portfolioId: String?,
        ctx: CallContext,
    ): List<LoaderRun> = store.list(ctx.tenantId, portfolioId).map { it.run }

    /** Re-parse + map, classify each row (new / duplicate / error) against Midas-core's existing ids. */
    suspend fun preview(
        runId: String,
        ctx: CallContext,
    ): LoaderPreview {
        val stored = stored(runId, ctx)
        val drafts = draftsOf(stored)
        val existing = client.existingExternalIds(stored.run.portfolioId, ctx)

        var new = 0
        var dup = 0
        var err = 0
        val rows =
            drafts.map { d ->
                val (decision, note) =
                    when {
                        d.error != null -> {
                            err++
                            PreviewDecision.PV_ERROR to d.error
                        }
                        d.draft.externalId.isNotBlank() && existing.contains(d.draft.externalId) -> {
                            dup++
                            PreviewDecision.PV_DUPLICATE to "duplicate of ${d.draft.externalId}"
                        }
                        else -> {
                            new++
                            PreviewDecision.PV_NEW to ""
                        }
                    }
                PreviewRow
                    .newBuilder()
                    .setSourceRowIndex(d.sourceRowIndex)
                    .setDraft(d.draft)
                    .setDecision(decision)
                    .setNote(note)
                    .build()
            }
        return LoaderPreview
            .newBuilder()
            .setLoaderRunId(runId)
            .addAllRows(rows)
            .setSummary(
                PreviewSummary
                    .newBuilder()
                    .setNewCount(new)
                    .setDuplicateCount(dup)
                    .setErrorCount(err)
                    .build(),
            ).build()
    }

    /** Resolve assets, fill `asset_id`, and commit the non-error drafts through Midas-core. */
    suspend fun commit(
        runId: String,
        skipExisting: Boolean,
        ctx: CallContext,
    ): BatchResult {
        val stored = stored(runId, ctx)
        save(
            stored,
            stored.run
                .toBuilder()
                .setStatus(LoaderRunStatus.LR_COMMITTING)
                .build(),
        )

        val drafts = draftsOf(stored)
        val errorCount = drafts.count { it.error != null }
        val assetCache = mutableMapOf<String, String>()
        val transactions =
            drafts.filter { it.error == null }.map { d ->
                val symbol = d.symbol.ifBlank { "CASH.${d.draft.currency}" }
                val assetId = assetCache.getOrPut(symbol) { client.resolveAsset(symbol, d.draft.currency, ctx) }
                d.draft
                    .toBuilder()
                    .setAssetId(assetId)
                    .build()
            }

        val result =
            try {
                client.batchInsert(transactions, skipExisting, ctx)
            } catch (e: Exception) {
                save(
                    stored,
                    stored.run
                        .toBuilder()
                        .setStatus(
                            LoaderRunStatus.LR_FAILED,
                        ).setErrorSummary(e.message.orEmpty())
                        .build(),
                )
                throw e
            }

        save(
            stored,
            stored.run
                .toBuilder()
                .setStatus(LoaderRunStatus.LR_COMPLETED)
                .setCompletedAt(now())
                .setRowCountCommitted(result.inserted)
                .setRowCountSkipped(result.skipped)
                .setRowCountFailed(result.failed + errorCount)
                .build(),
        )
        return result
    }

    // ---- internals ---------------------------------------------------------

    private fun stored(
        runId: String,
        ctx: CallContext,
    ): StoredRun = store.get(ctx.tenantId, runId) ?: throw LoaderRunNotFoundException(runId)

    private fun draftsOf(stored: StoredRun): List<DraftRow> {
        val bytes = blobStore.get(stored.blobRef) ?: throw ExcelParseException("blob ${stored.blobRef} missing")
        val template = registry.get(stored.run.brokerId)
        return mapper.map(parser.parse(bytes.inputStream(), template), template, stored.run.portfolioId)
    }

    private fun save(
        stored: StoredRun,
        run: LoaderRun,
    ) = store.save(stored.copy(run = run))

    private fun now(): Timestamp {
        val i = clock()
        return Timestamp
            .newBuilder()
            .setSeconds(i.epochSecond)
            .setNanos(i.nano)
            .build()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
