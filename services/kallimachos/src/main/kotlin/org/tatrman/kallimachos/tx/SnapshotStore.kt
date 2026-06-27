package org.tatrman.kallimachos.tx

/**
 * An in-memory store that can capture its state and hand back a restore thunk —
 * the seam the test [FakeTransactor] uses to prove the one-tx rollback invariant
 * without a live database.
 */
interface SnapshotStore {
    /** Capture current state; the returned thunk restores it when invoked. */
    fun snapshot(): () -> Unit
}

/**
 * Snapshot transactor: snapshots every store before the block and restores them
 * all if it throws — the in-memory equivalent of an Exposed `transaction { }`
 * rollback (architecture §13). Used both as the mocked-unit rollback proof AND
 * as the boundary for the running in-memory profile. Sequence counters are
 * intentionally NOT rolled back, mirroring real PG sequence semantics ("rollback
 * leaves no data", ids are simply not reused).
 */
class SnapshotTransactor(
    private vararg val stores: SnapshotStore,
) : Transactor {
    override fun <T> inTransaction(block: () -> T): T {
        val restores = stores.map { it.snapshot() }
        return try {
            block()
        } catch (e: Throwable) {
            restores.forEach { it() }
            throw e
        }
    }
}
