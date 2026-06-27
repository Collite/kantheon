package org.tatrman.kantheon.midas.loaders.googlefinance.sheets

/**
 * The seam to the live Google Sheet of `GOOGLEFINANCE` formulas (Stage 3.6 T2). All
 * pairs/tickers are read in **one** range fetch (the quota-mitigation in plan §6) and
 * returned as raw rows for [SheetParser]. The real google-sheets-api + service-account
 * implementation is **integration-deferred** (exercised in Stream T against a fixture
 * Sheet); the unit gate runs an in-memory [FixtureSheetsSource].
 */
fun interface SheetsSource {
    /** Read an A1 range (e.g. `"FX!A2:C"`) as rows of trimmed cell strings. */
    fun readRange(range: String): List<List<String>>
}

/** In-memory [SheetsSource] for the unit gate (and local boot): canned rows per range. */
class FixtureSheetsSource(
    private val byRange: Map<String, List<List<String>>>,
) : SheetsSource {
    override fun readRange(range: String): List<List<String>> = byRange[range].orEmpty()
}
