package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.kallimachos.tx.Transactor

/**
 * Live transactor — wraps Exposed `transaction { }` against the `kallimachos`
 * database. The single boundary the ingestion fan-out runs inside; a throw rolls
 * every plane's write back (architecture §13). Integration-verified (the
 * mocked-unit proof uses `FakeTransactor`).
 */
class ExposedTransactor(
    private val database: Database,
) : Transactor {
    override fun <T> inTransaction(block: () -> T): T = transaction(database) { block() }
}
