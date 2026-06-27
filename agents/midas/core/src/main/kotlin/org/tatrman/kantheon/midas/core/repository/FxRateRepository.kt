package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.tatrman.kantheon.midas.core.infra.FxRatesTable
import org.tatrman.kantheon.midas.core.infra.toDecimalOrZero
import org.tatrman.kantheon.midas.core.infra.toDecimalString
import org.tatrman.kantheon.midas.core.infra.toLocalDate
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.v1.FxRate
import shared.libs.db.common.DatabaseConnection
import java.time.LocalDate

/**
 * FX rates repository (contracts §2.7). fx_rates is **global** (no tenant_id, no
 * RLS), so these run outside [org.tatrman.kantheon.midas.core.tenant.TenantContext]
 * — a plain `db.query`. POST upserts on the `(from_ccy, to_ccy, rate_date)` PK
 * (last write wins; Google-Finance re-runs are idempotent).
 */
class FxRateRepository(
    private val db: DatabaseConnection,
) {
    fun upsert(rate: FxRate): FxRate =
        db.query {
            val date = rate.rateDate.toLocalDate()
            FxRatesTable.upsert {
                it[fromCcy] = rate.fromCcy
                it[toCcy] = rate.toCcy
                it[rateDate] = date
                it[FxRatesTable.rate] = rate.rate.toDecimalOrZero()
                it[rateSource] = rate.source
            }
            getInTx(rate.fromCcy, rate.toCcy, date) ?: error("fx_rate vanished after upsert")
        }

    fun list(
        fromCcy: String?,
        toCcy: String?,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): List<FxRate> =
        db.query {
            val query = FxRatesTable.selectAll()
            fromCcy?.let { c -> query.andWhere { FxRatesTable.fromCcy eq c } }
            toCcy?.let { c -> query.andWhere { FxRatesTable.toCcy eq c } }
            fromDate?.let { d -> query.andWhere { FxRatesTable.rateDate greaterEq d } }
            toDate?.let { d -> query.andWhere { FxRatesTable.rateDate lessEq d } }
            query
                .orderBy(FxRatesTable.rateDate to SortOrder.DESC)
                .limit(MAX_PAGE_SIZE)
                .map { it.toFxRate() }
        }

    private fun getInTx(
        from: String,
        to: String,
        date: LocalDate,
    ): FxRate? =
        FxRatesTable
            .selectAll()
            .where {
                (FxRatesTable.fromCcy eq from) and (FxRatesTable.toCcy eq to) and (FxRatesTable.rateDate eq date)
            }.firstOrNull()
            ?.toFxRate()

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toFxRate(): FxRate =
        FxRate
            .newBuilder()
            .setFromCcy(this[FxRatesTable.fromCcy])
            .setToCcy(this[FxRatesTable.toCcy])
            .setRateDate(this[FxRatesTable.rateDate].toProtoTimestamp())
            .setRate(this[FxRatesTable.rate].toDecimalString())
            .setSource(this[FxRatesTable.rateSource])
            .build()
}
