package org.tatrman.kantheon.midas.loaders.googlefinance.sheets

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Stage 3.6 T1 — the GOOGLEFINANCE Sheet parser: extracts {pair, rate, as_of} / {symbol,
 * price, as_of} and skips transient cell states (#N/A, Loading…, blanks) without sinking
 * the batch.
 */
class SheetParserSpec :
    StringSpec({

        "FX rows parse (pair separators / / : / 6-letter) and skip transient cells" {
            val rows =
                listOf(
                    listOf("USD/EUR", "0.9231", "2026-06-27"),
                    listOf("USD:GBP", "0.7850", "2026-06-27"),
                    listOf("USDCHF", "0.8900", "2026-06-27"),
                    listOf("USD/JPY", "#N/A", "2026-06-27"), // transient → skipped
                    listOf("", "", ""), // blank → skipped
                )
            val out = SheetParser.fxRates(rows)
            out shouldHaveSize 3
            out[0] shouldBe FxQuote("USD", "EUR", BigDecimal("0.9231"), LocalDate.of(2026, 6, 27))
            out[2].fromCcy shouldBe "USD"
            out[2].toCcy shouldBe "CHF"
        }

        "locale-tolerant numbers + non-ISO dates parse (cs/eu comma decimal, dotted/slashed dates)" {
            val rows =
                listOf(
                    listOf("USD/EUR", "0,92", "27.6.2026"), // cs comma decimal + dotted date
                    listOf("USD/GBP", "1.234,56", "27/06/2026"), // eu thousands-dot + slashed
                    listOf("USD/JPY", "1,234.56", "6/27/2026"), // us thousands-comma + us slashed
                )
            val out = SheetParser.fxRates(rows)
            out shouldHaveSize 3
            out[0] shouldBe FxQuote("USD", "EUR", BigDecimal("0.92"), LocalDate.of(2026, 6, 27))
            out[1].rate shouldBe BigDecimal("1234.56")
            out[1].asOf shouldBe LocalDate.of(2026, 6, 27)
            out[2].rate shouldBe BigDecimal("1234.56")
            out[2].asOf shouldBe LocalDate.of(2026, 6, 27)
        }

        "price rows parse and uppercase the symbol; a non-numeric price is skipped" {
            val rows =
                listOf(
                    listOf("aapl", "201.34", "2026-06-27"),
                    listOf("MSFT", "Loading...", "2026-06-27"), // transient → skipped
                    listOf("GOOG", "150.10", "2026-06-27"),
                )
            val out = SheetParser.prices(rows)
            out shouldHaveSize 2
            out[0] shouldBe PriceQuote("AAPL", BigDecimal("201.34"), LocalDate.of(2026, 6, 27))
            out[1].symbol shouldBe "GOOG"
        }
    })
