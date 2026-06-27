package org.tatrman.kallimachos.tx

/**
 * The transaction boundary for the one-tx ingestion fan-out (architecture §13:
 * "ingestion fan-out is one transaction; the only non-atomic edge is the
 * embedding call"). The service runs the relational + full-text (P2: + vector +
 * graph) writes inside a single [inTransaction]; a throw rolls the whole fan-out
 * back, leaving nothing in any plane.
 *
 *  - [org.tatrman.kallimachos.adapters.ExposedTransactor] wraps Exposed
 *    `transaction { }` (live PG; integration-verified).
 *  - `FakeTransactor` (test) snapshots the in-memory adapters and restores them
 *    on a throw — the mocked-unit proof of the rollback invariant.
 */
interface Transactor {
    fun <T> inTransaction(block: () -> T): T
}
