package org.tatrman.kantheon.midas.loaders.googlefinance.poller

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.googlefinance.client.MidasCoreClient
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.SheetParser
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.SheetsSource
import org.tatrman.kantheon.midas.v1.FxRate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A currency pair to refresh (base → quote). */
data class CurrencyPair(
    val fromCcy: String,
    val toCcy: String,
)

/** The seam to the set of pairs that need a daily rate — derived from `portfolios.base_currency`
 *  + asset currencies (live DB; integration-deferred). A fake supplies it on the unit gate. */
fun interface PairSource {
    fun activePairs(): List<CurrencyPair>
}

/** What a poller run did (for the run-history record). */
data class PollResult(
    val requested: Int,
    val upserted: Int,
    val skipped: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * The FX-rate poller (Stage 3.6 T3) — runs nightly (Quartz @ 23:00 UTC; the cron binding is
 * the deploy/runtime concern). Reads the active currency pairs, fetches the whole Sheet of
 * `GOOGLEFINANCE("CURRENCY:…")` rows in one read, and upserts each requested pair through
 * Midas-core. Upsert is idempotent on `(from, to, rate_date)`, so a re-run (manual `:trigger`
 * or a retried schedule) is safe. A pair the Sheet didn't resolve is skipped with a warning,
 * not a failure.
 */
class FxRatePoller(
    private val pairs: PairSource,
    private val sheets: SheetsSource,
    private val midas: MidasCoreClient,
    private val range: String = "FX!A2:C",
    private val source: String = "google-finance",
    private val now: () -> Instant = Instant::now,
    private val maxAge: Duration = Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(FxRatePoller::class.java)

    suspend fun run(bearer: String): PollResult {
        val requested = pairs.activePairs().distinct()
        if (requested.isEmpty()) return PollResult(0, 0, 0)
        val quotes =
            SheetParser
                .fxRates(sheets.readRange(range))
                .associateBy { it.fromCcy.uppercase() to it.toCcy.uppercase() }

        var upserted = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        var firstFailure: Exception? = null
        for (pair in requested) {
            val q = quotes[pair.fromCcy.uppercase() to pair.toCcy.uppercase()]
            if (q == null) {
                warnings += "no rate resolved for ${pair.fromCcy}/${pair.toCcy}"
                skipped++
                continue
            }
            val rateDate = q.asOf.atStartOfDay().toInstant(ZoneOffset.UTC)
            // Per-pair fault isolation: one upsert that fails (transient Midas-core error) is logged
            // and skipped, never aborting the rest of the nightly batch.
            try {
                midas.upsertFxRate(
                    FxRate
                        .newBuilder()
                        .setFromCcy(q.fromCcy)
                        .setToCcy(q.toCcy)
                        .setRate(q.rate.toPlainString())
                        .setRateDate(rateDate.toProtoTimestamp())
                        .setSource(source)
                        .build(),
                    bearer,
                )
            } catch (e: Exception) {
                if (firstFailure == null) firstFailure = e
                warnings += "upsert failed for ${q.fromCcy}/${q.toCcy}: ${e.message ?: e.javaClass.simpleName}"
                skipped++
                continue
            }
            upserted++
            // Stale-FX signal (T6): the Sheet served a rate older than maxAge — surface it as a
            // warning so the run record (and downstream "stale" badge) never treats it as live.
            val age = Duration.between(rateDate, now())
            if (age > maxAge) {
                warnings +=
                    "fx_rate_stale: ${q.fromCcy}/${q.toCcy} rate is ${age.toHours()}h old (> ${maxAge.toHours()}h)"
            }
        }
        // Partial failures are isolated (above); a *total* failure (nothing upserted, and the cause
        // was an error rather than an unresolved sheet) is a real run failure — let it surface.
        firstFailure?.let { if (upserted == 0) throw it }
        log.info("fx poller: requested={} upserted={} skipped={}", requested.size, upserted, skipped)
        return PollResult(requested.size, upserted, skipped, warnings)
    }

    private fun java.time.Instant.toProtoTimestamp(): com.google.protobuf.Timestamp =
        com.google.protobuf.Timestamp
            .newBuilder()
            .setSeconds(epochSecond)
            .setNanos(nano)
            .build()
}
