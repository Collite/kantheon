package org.tatrman.kantheon.midas.loaders.googlefinance.poller

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.PriceQuote
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.SheetParser
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.SheetsSource

/** An asset whose close price we refresh (its `GOOGLEFINANCE` ticker symbol). */
data class AssetTicker(
    val assetId: String,
    val symbol: String,
)

/** The seam to the active assets to price (live DB; integration-deferred — fake on the gate). */
fun interface AssetSource {
    fun activeAssets(): List<AssetTicker>
}

/** The write seam for close prices → the `asset_prices` table feeding `mv_portfolio_value_daily`.
 *  Integration-deferred (no price write endpoint / migration in v1); a fake records on the gate. */
fun interface PriceSink {
    fun write(
        assetId: String,
        quote: PriceQuote,
    )
}

/**
 * The market-price poller (Stage 3.6 T4) — runs nightly (Quartz @ 23:30 UTC). Reads the
 * active assets' tickers, fetches all close prices in one Sheet read, and writes each to the
 * `asset_prices` store (the `mv_portfolio_value_daily` feed). Symmetric to [FxRatePoller];
 * idempotent on `(asset_id, as_of)`. An un-resolved ticker is skipped with a warning.
 */
class PricePoller(
    private val assets: AssetSource,
    private val sheets: SheetsSource,
    private val sink: PriceSink,
    private val range: String = "PRICES!A2:C",
) {
    private val log = LoggerFactory.getLogger(PricePoller::class.java)

    fun run(): PollResult {
        val active = assets.activeAssets().distinct()
        if (active.isEmpty()) return PollResult(0, 0, 0)
        val bySymbol = SheetParser.prices(sheets.readRange(range)).associateBy { it.symbol.uppercase() }

        var written = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        var firstFailure: Exception? = null
        for (asset in active) {
            val q = bySymbol[asset.symbol.uppercase()]
            if (q == null) {
                warnings += "no price resolved for ${asset.symbol}"
                skipped++
                continue
            }
            // Per-asset fault isolation: a sink failure for one asset never sinks the whole batch.
            try {
                sink.write(asset.assetId, q)
                written++
            } catch (e: Exception) {
                if (firstFailure == null) firstFailure = e
                warnings += "price write failed for ${asset.symbol}: ${e.message ?: e.javaClass.simpleName}"
                skipped++
            }
        }
        // A *total* failure (nothing written, and the cause was an error) is a real run failure.
        firstFailure?.let { if (written == 0) throw it }
        log.info("price poller: requested={} written={} skipped={}", active.size, written, skipped)
        return PollResult(active.size, written, skipped, warnings)
    }
}
