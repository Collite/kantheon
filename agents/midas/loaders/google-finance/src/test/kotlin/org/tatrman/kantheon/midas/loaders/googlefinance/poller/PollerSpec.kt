package org.tatrman.kantheon.midas.loaders.googlefinance.poller

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.midas.loaders.googlefinance.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.FixtureSheetsSource
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.PriceQuote
import org.tatrman.kantheon.midas.v1.FxRate
import java.time.Instant

/**
 * Stage 3.6 T3/T4 — the FX + price pollers: the requested pair/asset set drives the upserts,
 * an un-resolved quote is skipped with a warning (not a failure), and a re-run is idempotent.
 */
class PollerSpec :
    StringSpec({

        val fxRows =
            mapOf("FX!A2:C" to listOf(listOf("USD/EUR", "0.92", "2026-06-27"), listOf("USD/GBP", "0.78", "2026-06-27")))

        "FX poller upserts the requested pairs and skips an unresolved one" {
            runTest {
                val captured = mutableListOf<FxRate>()
                val client = MidasCoreClient { rate, _ -> captured += rate }
                val poller =
                    FxRatePoller(
                        pairs = { listOf(CurrencyPair("USD", "EUR"), CurrencyPair("USD", "CHF")) }, // CHF not in sheet
                        sheets = FixtureSheetsSource(fxRows),
                        midas = client,
                    )
                val result = poller.run("tok")
                result.requested shouldBe 2
                result.upserted shouldBe 1
                result.skipped shouldBe 1
                captured shouldHaveSize 1
                captured[0].fromCcy shouldBe "USD"
                captured[0].toCcy shouldBe "EUR"
                captured[0].rate shouldBe "0.92"
                captured[0].source shouldBe "google-finance"
            }
        }

        "FX poller re-run is idempotent (same upserts, no growth in distinct pairs)" {
            runTest {
                val captured = mutableListOf<FxRate>()
                val client = MidasCoreClient { rate, _ -> captured += rate }
                val poller =
                    FxRatePoller({ listOf(CurrencyPair("USD", "EUR")) }, FixtureSheetsSource(fxRows), client)
                poller.run("tok")
                poller.run("tok")
                // upsert is idempotent on (from,to,date) — both runs target the same key
                captured.map { it.fromCcy to it.toCcy }.distinct() shouldHaveSize 1
            }
        }

        "FX poller flags a stale (>24h) rate with a warning but still upserts it (T6)" {
            runTest {
                val captured = mutableListOf<FxRate>()
                val poller =
                    FxRatePoller(
                        pairs = { listOf(CurrencyPair("USD", "EUR")) },
                        sheets = FixtureSheetsSource(fxRows), // sheet asOf = 2026-06-27
                        midas = { rate, _ -> captured += rate },
                        now = { Instant.parse("2026-07-01T00:00:00Z") }, // 4 days later → stale
                    )
                val result = poller.run("tok")
                result.upserted shouldBe 1
                captured shouldHaveSize 1
                result.warnings.any { it.startsWith("fx_rate_stale") } shouldBe true
            }
        }

        "FX poller isolates a single failing upsert — the rest of the batch still completes" {
            runTest {
                val poller =
                    FxRatePoller(
                        pairs = { listOf(CurrencyPair("USD", "EUR"), CurrencyPair("USD", "GBP")) },
                        sheets = FixtureSheetsSource(fxRows),
                        midas = { rate, _ ->
                            if (rate.toCcy == "EUR") throw IllegalStateException("transient 503")
                        },
                    )
                val result = poller.run("tok")
                result.requested shouldBe 2
                result.upserted shouldBe 1 // GBP still made it
                result.skipped shouldBe 1 // EUR failed, batch not aborted
                result.warnings.any { it.startsWith("upsert failed for USD/EUR") } shouldBe true
            }
        }

        "price poller writes resolved close prices to the sink and skips the rest" {
            val written = mutableListOf<Pair<String, PriceQuote>>()
            val poller =
                PricePoller(
                    assets = { listOf(AssetTicker("a-aapl", "AAPL"), AssetTicker("a-msft", "MSFT")) },
                    sheets =
                        FixtureSheetsSource(
                            mapOf("PRICES!A2:C" to listOf(listOf("AAPL", "201.34", "2026-06-27"))),
                        ),
                    sink = { id, q -> written += id to q },
                )
            val result = poller.run()
            result.requested shouldBe 2
            result.upserted shouldBe 1
            result.skipped shouldBe 1
            written shouldHaveSize 1
            written[0].first shouldBe "a-aapl"
            written[0].second.price.toPlainString() shouldBe "201.34"
        }
    })
