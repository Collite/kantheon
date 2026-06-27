package org.tatrman.kantheon.midas.core.infra

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import shared.libs.db.common.DatabaseConnection

/**
 * Refreshes `mv_position_current` after a write (Stage 1.4). The MV is owned by the
 * BYPASSRLS role `midas_mv_owner` (provisioned by the init job), so its refresh
 * reads across every tenant — midas_app alone is FORCE-RLS-bound and could not.
 * midas_app is a member of that role, so it may invoke REFRESH directly. Fails
 * open: a missing MV/role is logged and swallowed, so writes still succeed (the MV
 * is merely stale until the next successful run).
 *
 * v1 calls this synchronously once per write request (batch inserts coalesce
 * naturally). The async debounced PGNotificationListener (contracts §6.3) is
 * deferred to v1.x; the NOTIFY trigger is already installed (V0003).
 */
class MvRefresher(
    private val db: DatabaseConnection,
) {
    private val log = LoggerFactory.getLogger(MvRefresher::class.java)

    fun refreshPositions() {
        runCatching {
            db.query { TransactionManager.current().exec(REFRESH_SQL) }
        }.onFailure { e ->
            log.warn("mv_position_current refresh skipped: {}", e.message)
        }
    }

    companion object {
        // Runs as the MV owner (midas_mv_owner, BYPASSRLS) → reads across tenants.
        // Non-concurrent (CONCURRENTLY needs a populated-once precondition; a v1.x
        // optimization). The unique index supports the future switch.
        const val REFRESH_SQL = "REFRESH MATERIALIZED VIEW mv_position_current"
    }
}
