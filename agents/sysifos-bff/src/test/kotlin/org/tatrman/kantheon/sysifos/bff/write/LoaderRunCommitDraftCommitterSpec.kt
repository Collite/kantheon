package org.tatrman.kantheon.sysifos.bff.write

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.v1.Draft

class LoaderRunCommitDraftCommitterSpec :
    StringSpec({

        val caller = CallerIdentity("u1", "acme", "tok")

        fun draft(payload: String): Draft =
            Draft
                .newBuilder()
                .setDraftId("d-l")
                .setPayloadJson(payload)
                .build()

        "posts the loader commit and folds the batch counts into Committed" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    post(urlPathEqualTo("/api/v1/runs/r-1/commit")).willReturn(
                        aResponse().withHeader("Content-Type", "application/json").withBody(
                            """{"insertedCount":12,"skippedCount":340,"failedCount":0}""",
                        ),
                    ),
                )
                runTest {
                    val committer = LoaderRunCommitDraftCommitter(MidasCoreClient(wm.baseUrl()))
                    val outcome = committer.commit(draft("""{"loaderRunId":"r-1","skipExisting":true}"""), caller) { }
                    outcome shouldBe CommitOutcome.Committed("r-1", committedCount = 12, skippedCount = 340)
                }
                wm.verify(
                    postRequestedFor(urlPathEqualTo("/api/v1/runs/r-1/commit"))
                        .withRequestBody(equalToJson("""{"skip_existing":true,"confirm":true}""")),
                )
            } finally {
                wm.stop()
            }
        }

        "rejects a payload missing the loaderRunId" {
            runTest {
                val committer = LoaderRunCommitDraftCommitter(MidasCoreClient("http://localhost:1"))
                committer
                    .commit(
                        draft("""{"skipExisting":true}"""),
                        caller,
                    ) { }
                    .shouldBeInstanceOf<CommitOutcome.Rejected>()
            }
        }
    })
