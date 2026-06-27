package org.tatrman.kantheon.iris.inbox

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** A coarse investigation status transition (contracts §2.7 / Pythia §3.3 lifecycle subject). */
data class LifecycleEvent(
    val investigationId: String,
    val userId: String,
    val oldStatus: String,
    val newStatus: String,
    val ts: String,
)

/**
 * User-scoped fan-out of investigation lifecycle events to connected
 * `/v1/inbox/stream` SSE clients (PD-2, contracts §2.7). The producer is either a
 * NATS `pythia.lifecycle.{user_id}` subscriber (Pythia arc) or the
 * [PollingLifecycleDriver] fallback — both call [publish]; the route [subscribe]s.
 * `natsConnected = false` ⇒ the polling fallback is the active producer
 * (`iris_lifecycle_nats_connected` gauge).
 */
class LifecycleHub {
    private val log = LoggerFactory.getLogger(LifecycleHub::class.java)

    private data class Sub(
        val bearer: String,
        val sink: suspend (LifecycleEvent) -> Unit,
    )

    private val subs = ConcurrentHashMap<String, MutableSet<Sub>>()

    @Volatile
    var natsConnected: Boolean = false

    /** Register a sink for [userId]; returns an [AutoCloseable] that unregisters it
     *  and drops the user's set when its last subscriber leaves (no per-user leak). */
    fun subscribe(
        userId: String,
        bearer: String,
        sink: suspend (LifecycleEvent) -> Unit,
    ): AutoCloseable {
        val sub = Sub(bearer, sink)
        subs.getOrPut(userId) { ConcurrentHashMap.newKeySet() }.add(sub)
        return AutoCloseable {
            subs.compute(userId) { _, set ->
                set?.apply { remove(sub) }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Fan an event out to every sink registered for its user. */
    suspend fun publish(event: LifecycleEvent) {
        subs[event.userId]?.toList()?.forEach { sub ->
            runCatching { sub.sink(event) }
                .onFailure { log.debug("lifecycle sink failed for user {}", event.userId, it) }
        }
    }

    /** Active subscribers' (userId → a bearer), for the polling fallback. */
    fun activeUsers(): Map<String, String> =
        subs.entries
            .mapNotNull { (u, set) -> set.firstOrNull()?.let { u to it.bearer } }
            .toMap()
}

/**
 * Polling fallback (contracts §2.7): when NATS is absent/down, poll Pythia's
 * `GET /v1/investigations` every [intervalMs] for each subscribed user and
 * publish synthetic [LifecycleEvent]s on status change. Best-effort; a poll error
 * is logged and retried next tick. The live NATS subscriber supersedes this in
 * the Pythia arc (it sets `hub.natsConnected = true`).
 */
class PollingLifecycleDriver(
    private val pythia: PythiaClient,
    private val hub: LifecycleHub,
    private val intervalMs: Long,
    private val now: () -> String = {
        java.time.Instant
            .now()
            .toString()
    },
) {
    private val log = LoggerFactory.getLogger(PollingLifecycleDriver::class.java)
    private val lastStatus = ConcurrentHashMap<String, String>()

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (hub.natsConnected) continue // NATS is live — polling stands down
                runCatching { pollOnce() }.onFailure { log.debug("inbox poll failed", it) }
            }
        }

    /** One polling pass — visible for tests (drive it deterministically). */
    suspend fun pollOnce() {
        val seen = mutableSetOf<String>()
        for ((userId, bearer) in hub.activeUsers()) {
            for (inv in pythia.listInvestigations(userId, bearer)) {
                val prev = lastStatus.put(inv.id, inv.status)
                if (prev != null && prev != inv.status) {
                    hub.publish(LifecycleEvent(inv.id, userId, prev, inv.status, now()))
                }
                // Once terminal, stop tracking — it won't transition again.
                if (UserFacingStatus.of(inv.status).isTerminal) {
                    lastStatus.remove(inv.id)
                } else {
                    seen += inv.id
                }
            }
        }
        // Drop ids no longer reported by any active user (disconnected / aged out)
        // so lastStatus tracks only live, non-terminal investigations.
        lastStatus.keys.retainAll(seen)
    }
}
