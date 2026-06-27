@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kantheon.midas.core.infra.MvPositionCurrentTable
import org.tatrman.kantheon.midas.core.infra.toDecimalString
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.v1.Position
import shared.libs.db.common.DatabaseConnection
import kotlin.uuid.ExperimentalUuidApi

/**
 * Reads net positions from the `mv_position_current` materialized view (Stage 1.4).
 * Powers the `position.valuation` MCP tool. avg_cost / current_value / unrealised
 * P&L are NOT computed here — that is the calc module's job (Stage 3.3); this returns
 * the raw kind-signed quantity per holding (the MV's aggregate).
 *
 * The MV has no RLS, so this filters by `portfolio_id` (a globally-unique UUID)
 * directly rather than via [org.tatrman.kantheon.midas.core.tenant.TenantContext].
 */
class PositionRepository(
    private val db: DatabaseConnection,
) {
    fun positionsForPortfolio(portfolioId: String): List<Position> =
        db.query {
            MvPositionCurrentTable
                .selectAll()
                .where { MvPositionCurrentTable.portfolioId eq portfolioId.toUuidColumn() }
                .orderBy(MvPositionCurrentTable.assetId to SortOrder.ASC)
                .map { it.toPosition() }
        }

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toPosition(): Position =
        Position
            .newBuilder()
            .setPortfolioId(this[MvPositionCurrentTable.portfolioId].toUuidString())
            .setAssetId(this[MvPositionCurrentTable.assetId].toUuidString())
            .setQuantity(this[MvPositionCurrentTable.quantity].toDecimalString())
            .setAsOf(this[MvPositionCurrentTable.asOf].toProtoTimestamp())
            .build()
}
