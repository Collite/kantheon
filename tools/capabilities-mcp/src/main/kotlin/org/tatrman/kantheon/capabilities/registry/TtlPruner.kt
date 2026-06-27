package org.tatrman.kantheon.capabilities.registry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Periodically prunes runtime registrations whose heartbeat is stale.
 *
 * - Tick every [tickInterval] (default 30s).
 * - Mark entries pruned whose `lastHeartbeatAt < now - ttl`.
 * - Source-controlled fixtures (`lastHeartbeatAt == null`) are exempt.
 *
 * The TTL pruner is started by [App.kt] with the application coroutine scope
 * so it stops cleanly on shutdown.
 */
class TtlPruner(
    private val registry: InMemoryRegistry,
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val tickInterval: Duration = Duration.ofSeconds(30),
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job {
        check(job == null) { "TtlPruner already started" }
        val launched =
            scope.launch {
                while (isActive) {
                    delay(tickInterval.toMillis())
                    prune()
                }
            }
        job = launched
        return launched
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One-shot prune step; exposed for unit tests that drive the clock.
     */
    fun prune(): Int {
        val cutoff = Instant.now(clock).minus(ttl)
        return registry.markPrunedOlderThan(cutoff)
    }
}
