package org.tatrman.kantheon.pythia.dataplane

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.metis.v1.FitRequest
import org.tatrman.metis.v1.ModelKind
import org.tatrman.metis.v1.ProjectRequest

/**
 * Stage 4.2 T1 — the gRPC `MetisClient` against an in-process Metis fixture-server:
 * Fit/Project/GetStatus round-trip; `NOT_FOUND` → [MetisModelNotFoundException]
 * (re-fittable); `FAILED_PRECONDITION` → [MetisException]. Live Metis = integration.
 */
class MetisClientSpec :
    StringSpec({

        "fit returns the golden ARIMA order + AIC, and GetStatus round-trips" {
            runTest {
                val (server, channel) = startInProcess(FixtureMetisService())
                val client = GrpcMetisClient(channel)
                try {
                    val fit =
                        client.fit(
                            FitRequest
                                .newBuilder()
                                .setSessionId("s")
                                .setModelKind(ModelKind.ARIMA)
                                .setInputDf("series")
                                .setModelName("m1")
                                .build(),
                        )
                    fit.chosenOrder shouldBe "(1,1,1)(0,1,1,12)"
                    fit.aic shouldBe (-123.456789)
                    client.getStatus().sessions shouldBe 1
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "a NOT_FOUND model surfaces as MetisModelNotFoundException (re-fittable)" {
            runTest {
                val (server, channel) = startInProcess(FixtureMetisService(notFoundProjectModel = "m1"))
                val client = GrpcMetisClient(channel)
                try {
                    val ex =
                        shouldThrow<MetisModelNotFoundException> {
                            client.project(
                                ProjectRequest
                                    .newBuilder()
                                    .setSessionId(
                                        "s",
                                    ).setModelName("m1")
                                    .setOutputDf("o")
                                    .build(),
                            )
                        }
                    ex.modelName shouldBe "m1"
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }

        "a FAILED_PRECONDITION surfaces as MetisException with the gRPC code" {
            runTest {
                val (server, channel) = startInProcess(FixtureMetisService(failProjectPrecondition = true))
                val client = GrpcMetisClient(channel)
                try {
                    val ex =
                        shouldThrow<MetisException> {
                            client.project(
                                ProjectRequest
                                    .newBuilder()
                                    .setSessionId(
                                        "s",
                                    ).setModelName("m1")
                                    .setOutputDf("o")
                                    .build(),
                            )
                        }
                    ex.code shouldBe "FAILED_PRECONDITION"
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                }
            }
        }
    })
