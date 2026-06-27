package org.tatrman.kantheon.hebe.scheduler.offline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Store-and-forward outbox (P2 Stage 2.5 T3; architecture §7.1). The two seams
 * that cross the connectivity boundary — iris-bff turn dispatch (H→I) and channel
 * delivery (H→C) — become durable, idempotent queue rows that drain when
 * connectivity returns. This makes the §7 flow asynchronous at those seams
 * **without changing its shape**.
 *
 * The two sharp edges (risks note) are enforced here:
 *  - **idempotent enqueue** — re-enqueueing the same logical key is a no-op (no
 *    double-send across retries/restarts).
 *  - **idempotent drain** — a row sent once is never sent again; a send failure
 *    leaves the row for the next drain; ordering is preserved per destination.
 *
 * Backend-agnostic: this in-memory [store] models the contract; the SQLite/PG
 * row store (Phase 3) plugs in behind the same [OutboxStore] seam.
 */
data class OutboxItem(
    /** Logical idempotency key (e.g. `turn:<routineId>:<scheduledFor>`). */
    val key: String,
    val destination: String,
    val payload: String,
)

data class DrainResult(
    val sent: Int,
    val remaining: Int,
)

/** The durable row store the outbox sits on (in-memory here; PG/SQLite later). */
interface OutboxStore {
    suspend fun add(item: OutboxItem): Boolean // false if key already present

    suspend fun pending(): List<OutboxItem> // insertion order

    suspend fun remove(key: String)
}

class InMemoryOutboxStore : OutboxStore {
    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, OutboxItem>()

    override suspend fun add(item: OutboxItem): Boolean =
        mutex.withLock {
            if (rows.containsKey(item.key)) {
                false
            } else {
                rows[item.key] = item
                true
            }
        }

    override suspend fun pending(): List<OutboxItem> = mutex.withLock { rows.values.toList() }

    override suspend fun remove(key: String) {
        mutex.withLock { rows.remove(key) }
    }
}

class Outbox(
    private val store: OutboxStore = InMemoryOutboxStore(),
) {
    /** Enqueue a logical item. Returns false (no-op) if the key is already queued. */
    suspend fun enqueue(item: OutboxItem): Boolean = store.add(item)

    suspend fun pendingCount(): Int = store.pending().size

    /**
     * Drains pending rows in order, per destination. [send] returns `true` on a
     * successful send (row removed) and `false`/throws on failure (row retained,
     * draining stops for that destination to preserve ordering). Re-draining a
     * row that was already removed is naturally a no-op.
     */
    suspend fun drain(send: suspend (OutboxItem) -> Boolean): DrainResult {
        val pending = store.pending()
        val blockedDestinations = mutableSetOf<String>()
        var sent = 0
        for (item in pending) {
            if (item.destination in blockedDestinations) continue // keep ordering per destination
            val ok =
                try {
                    send(item)
                } catch (_: Exception) {
                    false
                }
            if (ok) {
                store.remove(item.key)
                sent++
            } else {
                blockedDestinations.add(item.destination)
            }
        }
        return DrainResult(sent = sent, remaining = store.pending().size)
    }
}
