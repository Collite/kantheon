package org.tatrman.kantheon.midas.loaders.excel.storage

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically prunes uploaded blobs older than [ttl] (Stage 1.5 T7 — the v1 "cron"
 * for `upload_blob_ref` cleanup; S3 lifecycle replaces it in v1.x). Runs in-process
 * on a daemon thread so a single loader pod needs no separate CronJob + shared volume.
 */
class BlobJanitor(
    private val store: FsBlobStore,
    private val ttl: Duration,
    private val interval: Duration,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(BlobJanitor::class.java)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "blob-janitor").apply { isDaemon = true }
        }

    fun start() {
        val periodSec = interval.seconds.coerceAtLeast(1)
        executor.scheduleAtFixedRate({
            runCatching {
                sweep()
            }.onFailure { log.warn("blob sweep failed: {}", it.message) }
        }, periodSec, periodSec, TimeUnit.SECONDS)
        log.info("blob janitor started (ttl={}, every={})", ttl, interval)
    }

    /** Delete blobs older than `now - ttl`; returns the count pruned. */
    fun sweep(): Int {
        val cutoff = clock().minus(ttl).toEpochMilli()
        val pruned = store.pruneOlderThan(cutoff)
        if (pruned > 0) log.info("pruned {} expired upload blob(s) older than {}", pruned, ttl)
        return pruned
    }

    fun stop() {
        executor.shutdownNow()
    }
}
