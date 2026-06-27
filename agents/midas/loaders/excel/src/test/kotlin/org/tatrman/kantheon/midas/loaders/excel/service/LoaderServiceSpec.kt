package org.tatrman.kantheon.midas.loaders.excel.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.midas.loaders.excel.client.BatchResult
import org.tatrman.kantheon.midas.loaders.excel.client.CallContext
import org.tatrman.kantheon.midas.loaders.excel.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.excel.fixtures.BrokerFixtures
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerRegistry
import org.tatrman.kantheon.midas.loaders.excel.parser.UnknownBrokerException
import org.tatrman.kantheon.midas.loaders.excel.storage.InMemoryBlobStore
import org.tatrman.kantheon.midas.loaders.excel.store.InMemoryLoaderRunStore
import org.tatrman.kantheon.midas.v1.LoaderRunStatus
import org.tatrman.kantheon.midas.v1.PreviewDecision
import org.tatrman.kantheon.midas.v1.Transaction

/** Records calls + returns scripted values, so the lifecycle is tested without Midas-core. */
private class FakeMidasCoreClient : MidasCoreClient {
    var existing: Set<String> = emptySet()
    var batch: BatchResult = BatchResult(inserted = 4, skipped = 0, failed = 0)
    val resolvedSymbols = mutableListOf<String>()
    var lastBatch: List<Transaction>? = null
    var lastSkipExisting: Boolean? = null

    override suspend fun resolveAsset(
        symbol: String,
        currency: String,
        ctx: CallContext,
    ): String {
        resolvedSymbols += symbol
        return "asset-$symbol"
    }

    override suspend fun existingExternalIds(
        portfolioId: String,
        ctx: CallContext,
    ): Set<String> = existing

    override suspend fun batchInsert(
        transactions: List<Transaction>,
        skipExisting: Boolean,
        ctx: CallContext,
    ): BatchResult {
        lastBatch = transactions
        lastSkipExisting = skipExisting
        return batch
    }
}

class LoaderServiceSpec :
    StringSpec({

        val ctx = CallContext(bearer = "jwt", tenantId = "tenant-1", userId = "user-1")
        val portfolio = "11111111-1111-1111-1111-111111111111"

        fun newService(client: MidasCoreClient): LoaderService {
            var n = 0
            return LoaderService(
                registry = BrokerRegistry.load(),
                client = client,
                store = InMemoryLoaderRunStore(),
                blobStore = InMemoryBlobStore(),
                idGen = { "run-${++n}" },
            )
        }

        "upload parses the fixture and creates a PREVIEW_READY run" {
            val run = newService(FakeMidasCoreClient()).upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            run.status shouldBe LoaderRunStatus.LR_PREVIEW_READY
            run.sourceKind shouldBe "EXCEL"
            run.rowCountTotal shouldBe 4
            run.tenantId shouldBe "tenant-1"
        }

        "an unknown broker_id is rejected" {
            shouldThrow<UnknownBrokerException> {
                newService(FakeMidasCoreClient()).upload(BrokerFixtures.alphaBytes(), "ghost", portfolio, ctx)
            }
        }

        "a file that doesn't match the broker template lands as FAILED with an error summary" {
            // alpha bytes have no "Activity" sheet → parsing as beta fails.
            val run = newService(FakeMidasCoreClient()).upload(BrokerFixtures.alphaBytes(), "beta", portfolio, ctx)
            run.status shouldBe LoaderRunStatus.LR_FAILED
            run.errorSummary.isNotBlank() shouldBe true
        }

        "re-upload of the same bytes returns the same loader_run_id (idempotent)" {
            val svc = newService(FakeMidasCoreClient())
            val first = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            val second = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            second.loaderRunId shouldBe first.loaderRunId
        }

        "preview classifies every row NEW when Midas-core has none of them" {
            val svc = newService(FakeMidasCoreClient())
            val run = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            val preview = svc.preview(run.loaderRunId, ctx)
            preview.rowsCount shouldBe 4
            preview.summary.newCount shouldBe 4
            preview.summary.duplicateCount shouldBe 0
            preview.rowsList.all { it.decision == PreviewDecision.PV_NEW } shouldBe true
        }

        "preview marks a row DUPLICATE when its external_id already exists in Midas-core" {
            val client = FakeMidasCoreClient().apply { existing = setOf("alpha:A1001") }
            val svc = newService(client)
            val run = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            val preview = svc.preview(run.loaderRunId, ctx)
            preview.summary.duplicateCount shouldBe 1
            preview.rowsList.first { it.decision == PreviewDecision.PV_DUPLICATE }.note shouldBe
                "duplicate of alpha:A1001"
        }

        "commit resolves each distinct asset once, fills asset_id, and forwards skip_existing" {
            val client = FakeMidasCoreClient().apply { batch = BatchResult(inserted = 4, skipped = 0, failed = 0) }
            val svc = newService(client)
            val run = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)

            val result = svc.commit(run.loaderRunId, skipExisting = true, ctx)

            // all 4 alpha rows are AAPL → one resolveAsset call.
            client.resolvedSymbols shouldContainExactly listOf("AAPL")
            client.lastSkipExisting shouldBe true
            client.lastBatch!!.size shouldBe 4
            client.lastBatch!!.all { it.assetId == "asset-AAPL" } shouldBe true

            result.inserted shouldBe 4
            svc.getRun(run.loaderRunId, ctx).status shouldBe LoaderRunStatus.LR_COMPLETED
            svc.getRun(run.loaderRunId, ctx).rowCountCommitted shouldBe 4
        }

        "a re-commit with skip_existing inserts nothing new" {
            val client = FakeMidasCoreClient().apply { batch = BatchResult(inserted = 0, skipped = 4, failed = 0) }
            val svc = newService(client)
            val run = svc.upload(BrokerFixtures.alphaBytes(), "alpha", portfolio, ctx)
            val result = svc.commit(run.loaderRunId, skipExisting = true, ctx)
            result.inserted shouldBe 0
            result.skipped shouldBe 4
            svc.getRun(run.loaderRunId, ctx).rowCountSkipped shouldBe 4
        }

        "preview/commit on an unknown run id is a not-found" {
            shouldThrow<LoaderRunNotFoundException> { newService(FakeMidasCoreClient()).preview("nope", ctx) }
        }
    })
