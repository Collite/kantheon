@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.infra

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed v1 table mappings over the Flyway-migrated V0001 schema (Stage 1.3 T3).
 *
 * Flyway owns the DDL (contracts §6.1); these `Table` objects mirror it for the
 * repository layer. Enums are stored as `text` (the allowed set is enforced by the
 * DDL CHECK + the proto↔DDL mapping locked in `MidasEnumDdlMappingSpec`), matching
 * the iris-bff idiom. Decimal precision/scale matches the NUMERIC columns exactly.
 */

object ClientsTable : Table("clients") {
    val clientId = uuid("client_id")
    val tenantId = uuid("tenant_id")
    val name = text("name")
    val contactEmail = text("contact_email").nullable()
    val contactPhone = text("contact_phone").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val createdByUserId = text("created_by_user_id")
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedByUserId = text("updated_by_user_id")
    override val primaryKey = PrimaryKey(clientId)
}

object PortfoliosTable : Table("portfolios") {
    val portfolioId = uuid("portfolio_id")
    val tenantId = uuid("tenant_id")
    val clientId = uuid("client_id")
    val name = text("name")
    val baseCurrency = text("base_currency")
    val portfolioType = text("portfolio_type")
    val costBasisMethod = text("cost_basis_method")
    val inceptionDate = date("inception_date").nullable()
    val status = text("status")
    val trackCash = bool("track_cash")
    val createdAt = timestampWithTimeZone("created_at")
    val createdByUserId = text("created_by_user_id")
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedByUserId = text("updated_by_user_id")
    override val primaryKey = PrimaryKey(portfolioId)
}

object AssetsTable : Table("assets") {
    val assetId = uuid("asset_id")
    val tenantId = uuid("tenant_id").nullable() // NULL for global assets
    val symbol = text("symbol")
    val isin = text("isin").nullable()
    val name = text("name")
    val kind = text("kind")
    val exchange = text("exchange").nullable()
    val currency = text("currency")
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val createdByUserId = text("created_by_user_id")
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedByUserId = text("updated_by_user_id")
    override val primaryKey = PrimaryKey(assetId)
}

object TransactionsTable : Table("transactions") {
    val transactionId = uuid("transaction_id")
    val tenantId = uuid("tenant_id")
    val portfolioId = uuid("portfolio_id")
    val assetId = uuid("asset_id")
    val kind = text("kind")
    val tradeDate = timestampWithTimeZone("trade_date")
    val settleDate = timestampWithTimeZone("settle_date").nullable()
    val quantity = decimal("quantity", 28, 8)
    val priceAmount = decimal("price_amount", 20, 4)
    val priceCurrency = text("price_currency").nullable()
    val feeAmount = decimal("fee_amount", 20, 4)
    val feeCurrency = text("fee_currency").nullable()
    val taxAmount = decimal("tax_amount", 20, 4)
    val taxCurrency = text("tax_currency").nullable()
    val totalAmount = decimal("total_amount", 20, 4)
    val totalCurrency = text("total_currency")
    val currency = text("currency")
    val externalId = text("external_id").nullable()
    val reversesTransactionId = uuid("reverses_transaction_id").nullable()
    val correlationId = uuid("correlation_id").nullable()
    val note = text("note").nullable()
    val txSource = text("source") // `source` collides with ColumnSet.source
    val recordedAt = timestampWithTimeZone("recorded_at")
    val recordedByUserId = text("recorded_by_user_id")
    override val primaryKey = PrimaryKey(transactionId)
}

/**
 * Read-only mapping over the `mv_position_current` materialized view (V0002) — net
 * position quantity per holding. The MV carries no RLS (MVs cannot), so reads must
 * scope explicitly; `portfolio_id` is a globally-unique UUID, so filtering by it
 * alone returns exactly one portfolio's rows. `quantity` is the kind-signed SUM.
 */
object MvPositionCurrentTable : Table("mv_position_current") {
    val portfolioId = uuid("portfolio_id")
    val assetId = uuid("asset_id")
    val tenantId = uuid("tenant_id")
    val quantity = decimal("quantity", 28, 8)
    val asOf = timestampWithTimeZone("as_of")
}

object FxRatesTable : Table("fx_rates") {
    val fromCcy = text("from_ccy")
    val toCcy = text("to_ccy")
    val rateDate = date("rate_date")
    val rate = decimal("rate", 20, 10)
    val rateSource = text("source") // `source` collides with ColumnSet.source
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(fromCcy, toCcy, rateDate)
}
