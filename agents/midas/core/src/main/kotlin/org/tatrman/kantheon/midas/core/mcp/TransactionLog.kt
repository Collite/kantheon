@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.mcp

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kantheon.midas.core.infra.PortfoliosTable
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.core.repository.MAX_PAGE_SIZE
import org.tatrman.kantheon.midas.core.repository.TransactionRepository
import org.tatrman.kantheon.midas.v1.Transaction
import shared.libs.db.common.DatabaseConnection

/**
 * The read seam the calc tools (cost-basis / fee-allocation / performance) use to replay
 * the transaction log (Stage 3.3). Keyed by `portfolio_id` / `transaction_id` (globally-
 * unique UUIDs), mirroring the `PositionRepository` no-tenant tool signature. A fake backs
 * the unit gate; [DbTransactionLog] is the live adapter (integration-deferred — the full
 * MCP per-request tenant threading is a separate concern, like Pythia's live seams).
 */
interface TransactionLog {
    /** Transactions for a portfolio (optionally one asset), trade-date ascending. */
    fun forPortfolio(
        portfolioId: String,
        assetId: String?,
    ): List<Transaction>

    /** A single transaction by id, or null if absent. */
    fun byId(transactionId: String): Transaction?
}

/**
 * DB-backed [TransactionLog]. A portfolio belongs to exactly one tenant, so `portfolio_id`
 * resolves the tenant; [forPortfolio] reads within that tenant's RLS via the repo. **Live
 * read path — integration-deferred** (exercised in Stream T); the unit gate runs a fake.
 * [byId] needs the request tenant (RLS denies an unscoped id lookup) — that threading lands
 * with the MCP per-request identity work, so the live adapter returns null for now.
 */
class DbTransactionLog(
    private val db: DatabaseConnection,
    private val repo: TransactionRepository,
) : TransactionLog {
    override fun forPortfolio(
        portfolioId: String,
        assetId: String?,
    ): List<Transaction> {
        val tenant = portfolioTenant(portfolioId) ?: return emptyList()
        // repo.list paginates DESC by trade_date; the FIFO ledger needs ascending order.
        return repo
            .list(tenant, page = 0, size = MAX_PAGE_SIZE, portfolioId = portfolioId, assetId = assetId, kind = null)
            .first
            .sortedBy { it.tradeDate.seconds }
    }

    override fun byId(transactionId: String): Transaction? = null

    private fun portfolioTenant(portfolioId: String): String? =
        db.query {
            PortfoliosTable
                .selectAll()
                .where { PortfoliosTable.portfolioId eq portfolioId.toUuidColumn() }
                .firstOrNull()
                ?.get(PortfoliosTable.tenantId)
                ?.toUuidString()
        }
}
