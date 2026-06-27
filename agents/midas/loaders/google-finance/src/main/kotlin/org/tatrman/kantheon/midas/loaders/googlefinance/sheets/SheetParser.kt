package org.tatrman.kantheon.midas.loaders.googlefinance.sheets

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A parsed FX rate from a `GOOGLEFINANCE("CURRENCY:FROMTO")` row. */
data class FxQuote(
    val fromCcy: String,
    val toCcy: String,
    val rate: BigDecimal,
    val asOf: LocalDate,
)

/** A parsed market close price from a `GOOGLEFINANCE("TICKER")` row. */
data class PriceQuote(
    val symbol: String,
    val price: BigDecimal,
    val asOf: LocalDate,
)

/**
 * Parses the tabular output of a Google Sheet of `GOOGLEFINANCE` formulas (Stage 3.6
 * T1/T2). The Sheet is laid out one quote per row; transient cell states
 * (`#N/A`, `Loading...`, blanks) are skipped rather than failing the whole batch, so a
 * single un-resolved ticker never sinks the nightly run. The parser is pure (over already-
 * fetched rows) so the live Sheets fetch ([SheetsSource]) stays a thin, mockable seam.
 *
 * FX rows:    `[ pair("USD/EUR" | "USD:EUR"), rate, asOfDate ]`
 * Price rows: `[ symbol, price, asOfDate ]`
 */
object SheetParser {
    private val TRANSIENT = setOf("", "#n/a", "#error!", "loading...", "#ref!", "#value!")

    fun fxRates(rows: List<List<String>>): List<FxQuote> =
        rows.mapNotNull { row ->
            val cells = row.map { it.trim() }
            if (cells.size < 3 || isTransient(cells)) return@mapNotNull null
            val (from, to) = splitPair(cells[0]) ?: return@mapNotNull null
            val rate = parseDecimal(cells[1]) ?: return@mapNotNull null
            val asOf = parseDate(cells[2]) ?: return@mapNotNull null
            FxQuote(from, to, rate, asOf)
        }

    fun prices(rows: List<List<String>>): List<PriceQuote> =
        rows.mapNotNull { row ->
            val cells = row.map { it.trim() }
            if (cells.size < 3 || isTransient(cells)) return@mapNotNull null
            val symbol = cells[0].uppercase()
            if (symbol.isBlank()) return@mapNotNull null
            val price = parseDecimal(cells[1]) ?: return@mapNotNull null
            val asOf = parseDate(cells[2]) ?: return@mapNotNull null
            PriceQuote(symbol, price, asOf)
        }

    private fun isTransient(cells: List<String>): Boolean = cells.take(2).any { it.lowercase() in TRANSIENT }

    /** `USD/EUR`, `USD:EUR`, or `USDEUR` (6 letters) → (USD, EUR). */
    private fun splitPair(raw: String): Pair<String, String>? {
        val p = raw.uppercase()
        val sep = p.indexOfFirst { it == '/' || it == ':' }
        return when {
            sep > 0 -> p.substring(0, sep).trim() to p.substring(sep + 1).trim()
            p.length == 6 && p.all { it.isLetter() } -> p.substring(0, 3) to p.substring(3)
            else -> null
        }.takeIf { it == null || (it.first.length == 3 && it.second.length == 3) }
    }

    private val DATE_FORMATS =
        listOf(
            DateTimeFormatter.ISO_LOCAL_DATE, // 2026-06-27
            DateTimeFormatter.ofPattern("d.M.uuuu"), // 27.6.2026 (cs)
            DateTimeFormatter.ofPattern("d/M/uuuu"), // 27/6/2026
            DateTimeFormatter.ofPattern("M/d/uuuu"), // 6/27/2026 (en-US)
        )

    /** Sheet locales differ: accept ISO + the common dotted/slashed calendar forms. The Sheet may
     *  append a clock time (`… 16:00:00`) — keep only the leading date token. */
    private fun parseDate(raw: String): LocalDate? {
        val token = raw.trim().substringBefore(' ').trim()
        if (token.isEmpty()) return null
        for (fmt in DATE_FORMATS) {
            runCatching { return LocalDate.parse(token, fmt) }
        }
        return null
    }

    /** Locale-tolerant decimal parse: strips a currency symbol/code and thousands separators, and
     *  accepts a comma decimal (`0,92` / `1.234,56`) as well as the canonical dot form (`1,234.56`). */
    private fun parseDecimal(raw: String): BigDecimal? {
        val cleaned = raw.replace(Regex("[^0-9,.\\-+]"), "")
        if (cleaned.isEmpty()) return null
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val normalized =
            when {
                // both separators present → the right-most one is the decimal point
                lastComma >= 0 && lastDot >= 0 ->
                    if (lastComma > lastDot) {
                        cleaned.replace(".", "").replace(',', '.')
                    } else {
                        cleaned.replace(",", "")
                    }
                // only a comma → treat it as the decimal separator (cs/eu)
                lastComma >= 0 -> cleaned.replace(',', '.')
                else -> cleaned
            }
        return normalized.toBigDecimalOrNull()
    }
}
