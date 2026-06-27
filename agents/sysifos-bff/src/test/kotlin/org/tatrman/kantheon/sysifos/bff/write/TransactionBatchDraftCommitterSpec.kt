package org.tatrman.kantheon.sysifos.bff.write

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.v1.BatchRowOutcome
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftKind
import org.tatrman.kantheon.sysifos.v1.SysifosStreamEvent
import org.tatrman.kantheon.sysifos.v1.TransactionBatchForm
import org.tatrman.kantheon.sysifos.v1.TransactionForm

class TransactionBatchDraftCommitterSpec :
    StringSpec({

        val caller = CallerIdentity("u1", "acme", "tok")

        fun row(
            assetId: String,
            qty: String,
        ): TransactionForm =
            TransactionForm
                .newBuilder()
                .setPortfolioId("p-1")
                .setAssetId(assetId)
                .setKind(TransactionKind.TX_BUY)
                .setQuantity(qty)
                .setCurrency("CZK")
                .build()

        fun batchDraft(): Draft {
            val form =
                TransactionBatchForm
                    .newBuilder()
                    .setPortfolioId("p-1")
                    .addRows(row("a-1", "10"))
                    .addRows(row("a-2", "5"))
                    .setSkipExisting(true)
                    .build()
            val json =
                com.google.protobuf.util.JsonFormat
                    .printer()
                    .print(form)
            return Draft
                .newBuilder()
                .setDraftId("d-batch")
                .setKind(DraftKind.DRAFT_TRANSACTION_BATCH)
                .setPayloadJson(json)
                .build()
        }

        "streams BR_COMMITTED for ok rows and BR_FAILED for the row Midas rejects" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/transactions:batch")).willReturn(
                        aResponse().withHeader("Content-Type", "application/json").withBody(
                            """{"insertedCount":1,"skippedCount":0,"failedCount":1,
                               "errors":[{"transaction":{"assetId":"a-2","quantity":"5","kind":"TX_BUY"},
                                          "reason":"price required","code":"VALIDATION"}]}""",
                        ),
                    ),
                )
                runTest {
                    val committer = TransactionBatchDraftCommitter(MidasCoreClient(wm.baseUrl()))
                    val events = mutableListOf<SysifosStreamEvent>()
                    val outcome = committer.commit(batchDraft(), caller) { events.add(it) }

                    val rowResults = events.filter { it.hasBatchRowResult() }.map { it.batchRowResult }
                    rowResults shouldHaveSize 2
                    rowResults[0].outcome shouldBe BatchRowOutcome.BR_COMMITTED
                    rowResults[1].outcome shouldBe BatchRowOutcome.BR_FAILED
                    rowResults[1].message shouldBe "price required"
                    outcome shouldBe CommitOutcome.Committed("d-batch", committedCount = 1, skippedCount = 0)
                }
            } finally {
                wm.stop()
            }
        }

        "a non-2xx batch response rejects the whole draft" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/transactions:batch"))
                        .willReturn(aResponse().withStatus(400).withBody("""{"code":"BAD"}""")),
                )
                runTest {
                    val committer = TransactionBatchDraftCommitter(MidasCoreClient(wm.baseUrl()))
                    val outcome = committer.commit(batchDraft(), caller) { }
                    (outcome is CommitOutcome.Rejected) shouldBe true
                }
            } finally {
                wm.stop()
            }
        }

        "a Midas error envelope is mapped to the rejection reason + a field error" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/transactions:batch")).willReturn(
                        aResponse().withStatus(409).withBody(
                            """{"error":{"code":"TRANSACTION_DUPLICATE_EXTERNAL_ID","message":"dup","field":"external_id"}}""",
                        ),
                    ),
                )
                runTest {
                    val committer = TransactionBatchDraftCommitter(MidasCoreClient(wm.baseUrl()))
                    val outcome = committer.commit(batchDraft(), caller) { }
                    outcome as CommitOutcome.Rejected
                    outcome.reason shouldBe "TRANSACTION_DUPLICATE_EXTERNAL_ID"
                    outcome.errors shouldHaveSize 1
                    outcome.errors[0].field shouldBe "external_id"
                    outcome.errors[0].message shouldBe "dup"
                }
            } finally {
                wm.stop()
            }
        }
    })
