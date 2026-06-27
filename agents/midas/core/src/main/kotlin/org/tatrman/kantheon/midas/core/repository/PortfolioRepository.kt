@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.midas.core.infra.PortfoliosTable
import org.tatrman.kantheon.midas.core.infra.dbEnum
import org.tatrman.kantheon.midas.core.infra.toDbEnum
import org.tatrman.kantheon.midas.core.infra.toLocalDate
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.core.tenant.TenantContext
import org.tatrman.kantheon.midas.v1.CostBasisMethod
import org.tatrman.kantheon.midas.v1.Portfolio
import org.tatrman.kantheon.midas.v1.PortfolioStatus
import org.tatrman.kantheon.midas.v1.PortfolioType
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Portfolios repository (contracts §2.2). RLS-scoped via [TenantContext.withTenant]. */
class PortfolioRepository(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
) {
    fun create(
        tenantId: String,
        userId: String,
        p: Portfolio,
    ): Portfolio =
        TenantContext.withTenant(db, tenantId) {
            val id = UUID.randomUUID()
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            PortfoliosTable.insert {
                it[portfolioId] = id.toString().toUuidColumn()
                it[PortfoliosTable.tenantId] = tenantId.toUuidColumn()
                it[clientId] = p.clientId.toUuidColumn()
                it[name] = p.name
                it[baseCurrency] = p.baseCurrency
                it[portfolioType] =
                    if (p.portfolioType ==
                        PortfolioType.UNRECOGNIZED
                    ) {
                        "BROKERAGE"
                    } else {
                        p.portfolioType.toDbEnum("PORTFOLIO_")
                    }
                it[costBasisMethod] = "FIFO"
                it[inceptionDate] = if (p.hasInceptionDate()) p.inceptionDate.toLocalDate() else null
                it[status] = "ACTIVE"
                it[trackCash] = p.trackCash
                it[createdAt] = now
                it[createdByUserId] = userId
                it[updatedAt] = now
                it[updatedByUserId] = userId
            }
            getInTx(id) ?: error("portfolio vanished after insert")
        }

    fun get(
        tenantId: String,
        id: UUID,
    ): Portfolio? = TenantContext.withTenant(db, tenantId) { getInTx(id) }

    fun list(
        tenantId: String,
        page: Int,
        size: Int,
        clientId: String?,
        status: String?,
    ): Pair<List<Portfolio>, Int> =
        TenantContext.withTenant(db, tenantId) {
            val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
            val safePage = page.coerceAtLeast(0)
            val query = PortfoliosTable.selectAll()
            clientId?.let { c -> query.andWhere { PortfoliosTable.clientId eq c.toUuidColumn() } }
            status?.let { s -> query.andWhere { PortfoliosTable.status eq s } }
            val total = query.count().toInt()
            val items =
                query
                    .orderBy(PortfoliosTable.name to SortOrder.ASC)
                    .limit(safeSize)
                    .offset(safePage.toLong() * safeSize)
                    .map { it.toPortfolio() }
            items to total
        }

    fun update(
        tenantId: String,
        userId: String,
        id: UUID,
        p: Portfolio,
    ): Portfolio? =
        TenantContext.withTenant(db, tenantId) {
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            val n =
                PortfoliosTable.update({ PortfoliosTable.portfolioId eq id.toString().toUuidColumn() }) {
                    it[name] = p.name
                    it[baseCurrency] = p.baseCurrency
                    if (p.portfolioType !=
                        PortfolioType.UNRECOGNIZED
                    ) {
                        it[portfolioType] = p.portfolioType.toDbEnum("PORTFOLIO_")
                    }
                    if (p.hasInceptionDate()) it[inceptionDate] = p.inceptionDate.toLocalDate()
                    it[trackCash] = p.trackCash
                    it[updatedAt] = now
                    it[updatedByUserId] = userId
                }
            if (n == 0) null else getInTx(id)
        }

    fun archive(
        tenantId: String,
        userId: String,
        id: UUID,
    ): Portfolio? =
        TenantContext.withTenant(db, tenantId) {
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            val n =
                PortfoliosTable.update({ PortfoliosTable.portfolioId eq id.toString().toUuidColumn() }) {
                    it[status] = "ARCHIVED"
                    it[updatedAt] = now
                    it[updatedByUserId] = userId
                }
            if (n == 0) null else getInTx(id)
        }

    private fun getInTx(id: UUID): Portfolio? =
        PortfoliosTable
            .selectAll()
            .where { PortfoliosTable.portfolioId eq id.toString().toUuidColumn() }
            .firstOrNull()
            ?.toPortfolio()

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toPortfolio(): Portfolio {
        val b =
            Portfolio
                .newBuilder()
                .setPortfolioId(this[PortfoliosTable.portfolioId].toUuidString())
                .setTenantId(this[PortfoliosTable.tenantId].toUuidString())
                .setClientId(this[PortfoliosTable.clientId].toUuidString())
                .setName(this[PortfoliosTable.name])
                .setBaseCurrency(this[PortfoliosTable.baseCurrency])
                .setPortfolioType(dbEnum(this[PortfoliosTable.portfolioType], "PORTFOLIO_", PortfolioType::valueOf))
                .setCostBasisMethod(
                    dbEnum(this[PortfoliosTable.costBasisMethod], "COST_BASIS_", CostBasisMethod::valueOf),
                ).setStatus(dbEnum(this[PortfoliosTable.status], "PORTFOLIO_", PortfolioStatus::valueOf))
                .setTrackCash(this[PortfoliosTable.trackCash])
                .setCreatedAt(this[PortfoliosTable.createdAt].toProtoTimestamp())
                .setUpdatedAt(this[PortfoliosTable.updatedAt].toProtoTimestamp())
        this[PortfoliosTable.inceptionDate]?.let { b.inceptionDate = it.toProtoTimestamp() }
        return b.build()
    }
}
