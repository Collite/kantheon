package org.tatrman.kantheon.midas.loaders.googlefinance.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.midas.loaders.googlefinance.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.googlefinance.poller.FxRatePoller
import org.tatrman.kantheon.midas.loaders.googlefinance.poller.PricePoller
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.FixtureSheetsSource

/**
 * Stage 3.6 T5 — the manual `:trigger` path records a run in the history, and a poller
 * failure is captured as a FAILED run (never an unhandled crash).
 */
class LoaderServiceSpec :
    StringSpec({

        fun service(fx: FxRatePoller): Pair<LoaderService, RunStore> {
            val runs = RunStore()
            val prices = PricePoller({ emptyList() }, FixtureSheetsSource(emptyMap()), { _, _ -> })
            return LoaderService(fx, prices, runs, bearer = { "tok" }) to runs
        }

        "a successful FX trigger records a SUCCESS run with the counts" {
            runTest {
                val fx =
                    FxRatePoller(
                        pairs = {
                            listOf(
                                org.tatrman.kantheon.midas.loaders.googlefinance.poller
                                    .CurrencyPair("USD", "EUR"),
                            )
                        },
                        sheets =
                            FixtureSheetsSource(
                                mapOf("FX!A2:C" to listOf(listOf("USD/EUR", "0.92", "2026-06-27"))),
                            ),
                        midas = MidasCoreClient { _, _ -> },
                    )
                val (svc, runs) = service(fx)
                val run = svc.trigger(RunKind.FX_RATES)
                run.status shouldBe "SUCCESS"
                run.processed shouldBe 1
                runs.get(run.id)!!.status shouldBe "SUCCESS"
                runs.list().size shouldBe 1
            }
        }

        "a poller failure is captured as a FAILED run, not a crash" {
            runTest {
                val fx =
                    FxRatePoller(
                        pairs = {
                            listOf(
                                org.tatrman.kantheon.midas.loaders.googlefinance.poller
                                    .CurrencyPair("USD", "EUR"),
                            )
                        },
                        sheets =
                            FixtureSheetsSource(
                                mapOf("FX!A2:C" to listOf(listOf("USD/EUR", "0.92", "2026-06-27"))),
                            ),
                        midas = MidasCoreClient { _, _ -> error("midas-core unreachable") },
                    )
                val (svc, _) = service(fx)
                val run = svc.trigger(RunKind.FX_RATES)
                run.status shouldBe "FAILED"
                run.message shouldBe "midas-core unreachable"
            }
        }
    })
