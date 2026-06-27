@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.midas.core.infra.AssetsTable
import org.tatrman.kantheon.midas.core.infra.dbEnum
import org.tatrman.kantheon.midas.core.infra.toDbEnum
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.core.tenant.TenantContext
import org.tatrman.kantheon.midas.v1.Asset
import org.tatrman.kantheon.midas.v1.AssetKind
import org.tatrman.kantheon.midas.v1.AssetStatus
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Assets repository (contracts §2.3). RLS-scoped via [TenantContext.withTenant]. */
class AssetRepository(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
) {
    fun create(
        tenantId: String,
        userId: String,
        a: Asset,
    ): Asset =
        TenantContext.withTenant(db, tenantId) {
            val id = UUID.randomUUID()
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            AssetsTable.insert {
                it[assetId] = id.toString().toUuidColumn()
                it[AssetsTable.tenantId] = tenantId.toUuidColumn()
                it[symbol] = a.symbol
                it[isin] = a.isin.ifBlank { null }
                it[name] = a.name
                it[kind] = if (a.kind == AssetKind.UNRECOGNIZED) "STOCK" else a.kind.toDbEnum("ASSET_")
                it[exchange] = a.exchange.ifBlank { null }
                it[currency] = a.currency
                it[status] = "ACTIVE"
                it[createdAt] = now
                it[createdByUserId] = userId
                it[updatedAt] = now
                it[updatedByUserId] = userId
            }
            getInTx(id) ?: error("asset vanished after insert")
        }

    fun get(
        tenantId: String,
        id: UUID,
    ): Asset? = TenantContext.withTenant(db, tenantId) { getInTx(id) }

    fun list(
        tenantId: String,
        page: Int,
        size: Int,
        symbol: String?,
        kind: String?,
        exchange: String?,
    ): Pair<List<Asset>, Int> =
        TenantContext.withTenant(db, tenantId) {
            val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
            val safePage = page.coerceAtLeast(0)
            val query = AssetsTable.selectAll()
            symbol?.let { s -> query.andWhere { AssetsTable.symbol eq s } }
            kind?.let { k -> query.andWhere { AssetsTable.kind eq k } }
            exchange?.let { e -> query.andWhere { AssetsTable.exchange eq e } }
            val total = query.count().toInt()
            val items =
                query
                    .orderBy(AssetsTable.symbol to SortOrder.ASC)
                    .limit(safeSize)
                    .offset(safePage.toLong() * safeSize)
                    .map { it.toAsset() }
            items to total
        }

    fun update(
        tenantId: String,
        userId: String,
        id: UUID,
        a: Asset,
    ): Asset? =
        TenantContext.withTenant(db, tenantId) {
            val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
            val n =
                AssetsTable.update({ AssetsTable.assetId eq id.toString().toUuidColumn() }) {
                    it[symbol] = a.symbol
                    it[isin] = a.isin.ifBlank { null }
                    it[name] = a.name
                    if (a.kind != AssetKind.UNRECOGNIZED) it[kind] = a.kind.toDbEnum("ASSET_")
                    it[exchange] = a.exchange.ifBlank { null }
                    it[currency] = a.currency
                    if (a.status != AssetStatus.UNRECOGNIZED && a.status != AssetStatus.ASSET_ACTIVE) {
                        it[status] = a.status.toDbEnum("ASSET_")
                    }
                    it[updatedAt] = now
                    it[updatedByUserId] = userId
                }
            if (n == 0) null else getInTx(id)
        }

    private fun getInTx(id: UUID): Asset? =
        AssetsTable
            .selectAll()
            .where { AssetsTable.assetId eq id.toString().toUuidColumn() }
            .firstOrNull()
            ?.toAsset()

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toAsset(): Asset {
        val b =
            Asset
                .newBuilder()
                .setAssetId(this[AssetsTable.assetId].toUuidString())
                .setSymbol(this[AssetsTable.symbol])
                .setName(this[AssetsTable.name])
                .setKind(dbEnum(this[AssetsTable.kind], "ASSET_", AssetKind::valueOf))
                .setCurrency(this[AssetsTable.currency])
                .setStatus(dbEnum(this[AssetsTable.status], "ASSET_", AssetStatus::valueOf))
                .setCreatedAt(this[AssetsTable.createdAt].toProtoTimestamp())
                .setUpdatedAt(this[AssetsTable.updatedAt].toProtoTimestamp())
        this[AssetsTable.tenantId]?.let { b.tenantId = it.toUuidString() }
        this[AssetsTable.isin]?.let { b.isin = it }
        this[AssetsTable.exchange]?.let { b.exchange = it }
        return b.build()
    }
}
