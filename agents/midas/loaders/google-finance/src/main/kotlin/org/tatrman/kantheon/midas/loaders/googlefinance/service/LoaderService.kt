package org.tatrman.kantheon.midas.loaders.googlefinance.service

import org.tatrman.kantheon.midas.loaders.googlefinance.poller.FxRatePoller
import org.tatrman.kantheon.midas.loaders.googlefinance.poller.PricePoller
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The loader kinds the poller schedules + the manual `:trigger` accepts. */
enum class RunKind { FX_RATES, MARKET_PRICES }

/** One poller execution (scheduled or manually triggered) — the run-history record. */
data class LoaderRun(
    val id: String,
    val kind: RunKind,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val requested: Int = 0,
    val processed: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
)

/** In-memory run history (Stage 3.6 T5 — DB-backed store is a documented follow-up, per the
 *  Excel-loader v1 precedent). Newest first. */
class RunStore {
    private val runs = ConcurrentHashMap<String, LoaderRun>()

    fun put(run: LoaderRun): LoaderRun {
        runs[run.id] = run
        return run
    }

    fun get(id: String): LoaderRun? = runs[id]

    fun list(): List<LoaderRun> = runs.values.sortedByDescending { it.startedAt }
}

/**
 * Orchestrates a poller run + records it in the [RunStore] (Stage 3.6 T5). The scheduler
 * (Quartz @ 23:00 / 23:30) and the `POST /runs:trigger` route both call [trigger]; a failure
 * is captured as a `FAILED` run (never an unhandled crash) so the history reflects reality.
 */
class LoaderService(
    private val fx: FxRatePoller,
    private val prices: PricePoller,
    private val runs: RunStore,
    private val bearer: () -> String,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun trigger(kind: RunKind): LoaderRun {
        val id = newId()
        val startedAt = clock()
        runs.put(LoaderRun(id, kind, "RUNNING", startedAt, null))
        return try {
            val result =
                when (kind) {
                    RunKind.FX_RATES -> fx.run(bearer())
                    RunKind.MARKET_PRICES -> prices.run()
                }
            runs.put(
                LoaderRun(
                    id = id,
                    kind = kind,
                    status = "SUCCESS",
                    startedAt = startedAt,
                    finishedAt = clock(),
                    requested = result.requested,
                    processed = result.upserted,
                    skipped = result.skipped,
                    message = result.warnings.joinToString("; "),
                ),
            )
        } catch (e: Exception) {
            runs.put(
                LoaderRun(id, kind, "FAILED", startedAt, clock(), message = e.message ?: e.javaClass.simpleName),
            )
        }
    }
}
