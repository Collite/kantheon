package org.tatrman.kantheon.midas.loaders.excel.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.kantheon.midas.loaders.excel.module
import org.tatrman.kantheon.midas.loaders.excel.client.BatchResult
import org.tatrman.kantheon.midas.loaders.excel.client.CallContext
import org.tatrman.kantheon.midas.loaders.excel.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.excel.fixtures.BrokerFixtures
import org.tatrman.kantheon.midas.loaders.excel.parser.BrokerRegistry
import org.tatrman.kantheon.midas.loaders.excel.service.LoaderService
import org.tatrman.kantheon.midas.loaders.excel.storage.InMemoryBlobStore
import org.tatrman.kantheon.midas.loaders.excel.store.InMemoryLoaderRunStore
import org.tatrman.kantheon.midas.v1.Transaction
import java.util.Base64

/** A test-only Midas-core that records the commit + scripts a 4-insert batch. */
class RecordingClient : MidasCoreClient {
    var lastBatch: List<Transaction>? = null

    override suspend fun resolveAsset(
        symbol: String,
        currency: String,
        ctx: CallContext,
    ): String = "asset-$symbol"

    override suspend fun existingExternalIds(
        portfolioId: String,
        ctx: CallContext,
    ): Set<String> = emptySet()

    override suspend fun batchInsert(
        transactions: List<Transaction>,
        skipExisting: Boolean,
        ctx: CallContext,
    ): BatchResult {
        lastBatch = transactions
        return BatchResult(inserted = transactions.size, skipped = 0, failed = 0)
    }
}

class LoaderRoutesSpec :
    StringSpec({

        val portfolio = "11111111-1111-1111-1111-111111111111"

        fun jwt(
            sub: String = "u1",
            tenant: String = "tenant-1",
        ): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = enc.encodeToString("""{"sub":"$sub","tenant":"$tenant"}""".toByteArray())
            return "$header.$payload."
        }

        "POST /uploads (multipart) → 202; preview + commit drive the full lifecycle" {
            val client = RecordingClient()
            val service =
                LoaderService(
                    registry = BrokerRegistry.load(),
                    client = client,
                    store = InMemoryLoaderRunStore(),
                    blobStore = InMemoryBlobStore(),
                )
            testApplication {
                application { module(service, BearerAuthenticator()) }

                val upload =
                    this.client.post("/api/v1/uploads") {
                        header(HttpHeaders.Authorization, "Bearer ${jwt()}")
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append("broker_id", "alpha")
                                    append("portfolio_id", portfolio)
                                    append(
                                        "file",
                                        BrokerFixtures.alphaBytes(),
                                        Headers.build {
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                "filename=\"alpha.xlsx\"",
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                    }
                upload.status shouldBe HttpStatusCode.Accepted
                val runId = Regex(""""loader_run_id":"([^"]+)"""").find(upload.bodyAsText())!!.groupValues[1]

                val preview =
                    this.client.get("/api/v1/runs/$runId/preview") {
                        header(HttpHeaders.Authorization, "Bearer ${jwt()}")
                    }
                preview.status shouldBe HttpStatusCode.OK
                preview.bodyAsText() shouldContain "\"newCount\":4"

                val commit =
                    this.client.post("/api/v1/runs/$runId/commit") {
                        header(HttpHeaders.Authorization, "Bearer ${jwt()}")
                        setBody("""{"skip_existing":true,"confirm":true}""")
                    }
                commit.status shouldBe HttpStatusCode.OK
                commit.bodyAsText() shouldContain "\"insertedCount\":4"
                client.lastBatch!!.size shouldBe 4
            }
        }

        "a request without a bearer is 401" {
            val service =
                LoaderService(
                    registry = BrokerRegistry.load(),
                    client = RecordingClient(),
                    store = InMemoryLoaderRunStore(),
                    blobStore = InMemoryBlobStore(),
                )
            testApplication {
                application { module(service, BearerAuthenticator()) }
                this.client.get("/api/v1/runs").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
