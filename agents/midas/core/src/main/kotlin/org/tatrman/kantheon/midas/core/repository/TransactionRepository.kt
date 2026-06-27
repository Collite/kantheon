@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.midas.core.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kantheon.midas.core.api.MidasErrorCode
import org.tatrman.kantheon.midas.core.api.MidasException
import org.tatrman.kantheon.midas.core.derivation.CashLegDerivation
import org.tatrman.kantheon.midas.core.infra.AssetsTable
import org.tatrman.kantheon.midas.core.infra.MvRefresher
import org.tatrman.kantheon.midas.core.infra.PortfoliosTable
import org.tatrman.kantheon.midas.core.infra.TransactionsTable
import org.tatrman.kantheon.midas.core.infra.dbEnum
import org.tatrman.kantheon.midas.core.infra.toDbEnum
import org.tatrman.kantheon.midas.core.infra.toDecimalOrZero
import org.tatrman.kantheon.midas.core.infra.toDecimalString
import org.tatrman.kantheon.midas.core.infra.toOffsetDateTime
import org.tatrman.kantheon.midas.core.infra.toProtoTimestamp
import org.tatrman.kantheon.midas.core.infra.toUuidColumn
import org.tatrman.kantheon.midas.core.infra.toUuidString
import org.tatrman.kantheon.midas.core.tenant.TenantContext
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource
import shared.libs.db.common.DatabaseConnection
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/** Hard cap on page size so a single `list` call can never load an unbounded slice. */
internal const val MAX_PAGE_SIZE = 500

/** A persisted security leg and its (optional) derived cash leg. */
data class InsertedLegs(
    val security: Transaction,
    val cash: Transaction?,
)

/** Outcome of a batch insert (contracts §2.4). */
data class BatchOutcome(
    val inserted: List<Transaction>,
    val skipped: Int,
)

/** A reversal entry and (for PATCH) the replacement entry. */
data class ReverseResult(
    val reversal: Transaction,
    val replacement: Transaction?,
)

/**
 * Transactions repository (contracts §2.4). The `transactions` table is
 * append-only (DB triggers forbid UPDATE/DELETE — edits are reversal entries).
 * Every write runs through [TenantContext.withTenant] (RLS), and derives the cash
 * counter-leg (§1.1.A) in the same transaction when the portfolio tracks cash.
 */
class TransactionRepository(
    private val db: DatabaseConnection,
    private val refresher: MvRefresher = MvRefresher(db),
    private val clock: () -> Instant = Instant::now,
) {
    fun insert(
        tenantId: String,
        userId: String,
        input: Transaction,
    ): InsertedLegs {
        val legs = TenantContext.withTenant(db, tenantId) { insertInTx(tenantId, userId, input) }
        refresher.refreshPositions()
        return legs
    }

    fun batchInsert(
        tenantId: String,
        userId: String,
        inputs: List<Transaction>,
        skipExisting: Boolean,
    ): BatchOutcome {
        val outcome =
            TenantContext.withTenant(db, tenantId) {
                val inserted = mutableListOf<Transaction>()
                var skipped = 0
                for (input in inputs) {
                    if (input.externalId.isNotBlank() && existingByExternalId(input.externalId) != null) {
                        if (skipExisting) {
                            skipped++
                            continue
                        }
                        throw MidasException.conflict(
                            MidasErrorCode.TRANSACTION_DUPLICATE_EXTERNAL_ID,
                            "transaction with external_id '${input.externalId}' already exists",
                            field = "external_id",
                        )
                    }
                    val legs = insertInTx(tenantId, userId, input)
                    inserted += legs.security
                    legs.cash?.let { inserted += it }
                }
                BatchOutcome(inserted, skipped)
            }
        refresher.refreshPositions()
        return outcome
    }

    fun get(
        tenantId: String,
        id: UUID,
    ): Transaction? =
        TenantContext.withTenant(db, tenantId) {
            TransactionsTable
                .selectAll()
                .where { TransactionsTable.transactionId eq id.toString().toUuidColumn() }
                .firstOrNull()
                ?.toTransaction()
        }

    fun list(
        tenantId: String,
        page: Int,
        size: Int,
        portfolioId: String?,
        assetId: String?,
        kind: String?,
    ): Pair<List<Transaction>, Int> =
        TenantContext.withTenant(db, tenantId) {
            val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
            val safePage = page.coerceAtLeast(0)
            // Filter + paginate in SQL (RLS still scopes to the tenant) — the
            // transactions table is append-only and unbounded, so never load it all.
            val query = TransactionsTable.selectAll()
            portfolioId?.let { p -> query.andWhere { TransactionsTable.portfolioId eq p.toUuidColumn() } }
            assetId?.let { a -> query.andWhere { TransactionsTable.assetId eq a.toUuidColumn() } }
            kind?.let { k -> query.andWhere { TransactionsTable.kind eq k } }
            val total = query.count().toInt()
            val items =
                query
                    .orderBy(TransactionsTable.tradeDate to SortOrder.DESC)
                    .limit(safeSize)
                    .offset(safePage.toLong() * safeSize)
                    .map { it.toTransaction() }
            items to total
        }

    /**
     * PATCH / DELETE (contracts §2.4 — append-only edits). Inserts a reversing
     * contra-entry for the original (same kind, negated quantity/total,
     * `reverses_transaction_id` set, `source = TX_SRC_REVERSAL`), cascades the
     * reversal to the original's derived cash leg via `correlation_id`, and — when
     * [replacement] is given (PATCH) — inserts the new entry (which derives its own
     * fresh cash leg). DELETE passes a null replacement.
     */
    fun reverseAndReplace(
        tenantId: String,
        userId: String,
        id: UUID,
        replacement: Transaction?,
        reason: String,
    ): ReverseResult? =
        TenantContext
            .withTenant(db, tenantId) {
                val original = rowById(id) ?: return@withTenant null
                val reversal = insertReversalOf(tenantId, userId, original, reason)
                findOriginalCashLeg(original.correlationId)?.let { insertReversalOf(tenantId, userId, it, reason) }
                val repl = replacement?.let { insertInTx(tenantId, userId, it).security }
                ReverseResult(reversal, repl)
            }.also { if (it != null) refresher.refreshPositions() }

    /** Balance entry preview (contracts §2.5) — read-only; proposes the ADJUSTMENT. */
    fun previewBalance(
        tenantId: String,
        req: org.tatrman.kantheon.midas.v1.BalanceEntryRequest,
    ): org.tatrman.kantheon.midas.v1.BalanceEntryPreview =
        TenantContext.withTenant(db, tenantId) {
            requirePortfolioAndAsset(req.portfolioId, req.assetId)
            buildPreview(req)
        }

    /** Balance entry commit (contracts §2.5) — re-runs the diff (race-safe) + inserts. */
    fun commitBalance(
        tenantId: String,
        userId: String,
        req: org.tatrman.kantheon.midas.v1.BalanceEntryRequest,
    ): Transaction {
        val committed =
            TenantContext.withTenant(db, tenantId) {
                requirePortfolioAndAsset(req.portfolioId, req.assetId)
                val preview = buildPreview(req)
                if (preview.diffQuantity.toDecimalOrZero().signum() == 0) {
                    throw MidasException.badRequest(
                        MidasErrorCode.BALANCE_ENTRY_NO_DIFF,
                        "target quantity equals current quantity; nothing to adjust",
                    )
                }
                insertRow(tenantId, userId, preview.proposedTransaction)
            }
        refresher.refreshPositions()
        return committed
    }

    // ---- internals (run inside an open withTenant transaction) -------------

    private fun rowById(id: UUID): Transaction? =
        TransactionsTable
            .selectAll()
            .where { TransactionsTable.transactionId eq id.toString().toUuidColumn() }
            .firstOrNull()
            ?.toTransaction()

    private fun insertReversalOf(
        tenantId: String,
        userId: String,
        original: Transaction,
        reason: String,
    ): Transaction {
        val reversal =
            original
                .toBuilder()
                .clearTransactionId()
                .clearExternalId() // avoid the unique (tenant, external_id) clash with the original
                .setQuantity(negate(original.quantity))
                .setTotal(
                    Money
                        .newBuilder()
                        .setAmount(negate(original.total.amount))
                        .setCurrency(original.total.currency),
                ).setReversesTransactionId(original.transactionId)
                .setSource(TransactionSource.TX_SRC_REVERSAL)
                .setNote(if (reason.isBlank()) "reversal" else "reversal: $reason")
                .build()
        return insertRow(tenantId, userId, reversal)
    }

    /** The original (non-reversal) cash leg sharing this correlation id, if any. */
    private fun findOriginalCashLeg(correlationId: String): Transaction? {
        if (correlationId.isBlank()) return null
        return TransactionsTable
            .selectAll()
            .where {
                (TransactionsTable.correlationId eq correlationId.toUuidColumn()) and
                    (TransactionsTable.reversesTransactionId.isNull())
            }.toList()
            .map { it.toTransaction() }
            .firstOrNull { it.kind == TransactionKind.TX_CASH_CREDIT || it.kind == TransactionKind.TX_CASH_DEBIT }
    }

    private fun requirePortfolioAndAsset(
        portfolioId: String,
        assetId: String,
    ) {
        val pOk =
            PortfoliosTable
                .selectAll()
                .where { PortfoliosTable.portfolioId eq portfolioId.toUuidColumn() }
                .any()
        val aOk =
            AssetsTable
                .selectAll()
                .where { AssetsTable.assetId eq assetId.toUuidColumn() }
                .any()
        if (!pOk || !aOk) {
            throw MidasException.notFound(
                MidasErrorCode.BALANCE_ENTRY_PORTFOLIO_OR_ASSET_NOT_FOUND,
                "portfolio or asset not found in tenant scope",
            )
        }
    }

    private fun buildPreview(
        req: org.tatrman.kantheon.midas.v1.BalanceEntryRequest,
    ): org.tatrman.kantheon.midas.v1.BalanceEntryPreview {
        val current = currentQuantity(req.portfolioId, req.assetId)
        val target = req.targetQuantity.toDecimalOrZero()
        val diff = target.subtract(current)
        val adjustment =
            Transaction
                .newBuilder()
                .setPortfolioId(req.portfolioId)
                .setAssetId(req.assetId)
                .setKind(TransactionKind.TX_ADJUSTMENT)
                .setTradeDate(req.asOf)
                .setQuantity(diff.toPlainString())
                .setCurrency("")
                .setSource(TransactionSource.TX_SRC_DERIVATION)
                .setNote(if (req.reason.isBlank()) "balance entry" else req.reason)
                .build()
        return org.tatrman.kantheon.midas.v1.BalanceEntryPreview
            .newBuilder()
            .setPortfolioId(req.portfolioId)
            .setAssetId(req.assetId)
            .setCurrentQuantity(current.toPlainString())
            .setTargetQuantity(target.toPlainString())
            .setDiffQuantity(diff.toPlainString())
            .setProposedTransaction(adjustment)
            .build()
    }

    private fun currentQuantity(
        portfolioId: String,
        assetId: String,
    ): BigDecimal =
        TransactionsTable
            .selectAll()
            .where {
                (TransactionsTable.portfolioId eq portfolioId.toUuidColumn()) and
                    (TransactionsTable.assetId eq assetId.toUuidColumn())
            }.fold(BigDecimal.ZERO) { acc, row ->
                // Net position uses the same kind→sign rule as mv_position_current
                // (stored quantity is a positive magnitude; direction comes from kind).
                val kind = dbEnum(row[TransactionsTable.kind], "TX_", TransactionKind::valueOf)
                val sign = CashLegDerivation.positionSign(kind)
                acc.add(row[TransactionsTable.quantity].multiply(BigDecimal(sign)))
            }

    private fun negate(s: String): String = if (s.isBlank()) "0" else s.toDecimalOrZero().negate().toPlainString()

    private fun insertInTx(
        tenantId: String,
        userId: String,
        input: Transaction,
    ): InsertedLegs {
        if (input.externalId.isNotBlank()) {
            existingByExternalId(input.externalId)?.let { existing ->
                throw MidasException.conflict(
                    MidasErrorCode.TRANSACTION_DUPLICATE_EXTERNAL_ID,
                    "transaction with external_id '${input.externalId}' already exists",
                    field = "external_id",
                    details = mapOf("existing_transaction_id" to existing.transactionId),
                )
            }
        }
        val correlationId = UUID.randomUUID().toString()
        val total = CashLegDerivation.cashAmount(input)
        val source =
            if (input.source ==
                TransactionSource.UNRECOGNIZED
            ) {
                TransactionSource.TX_SRC_MANUAL
            } else {
                input.source
            }
        val security =
            input
                .toBuilder()
                .setTotal(Money.newBuilder().setAmount(total.toPlainString()).setCurrency(input.currency))
                .setCorrelationId(correlationId)
                .setSource(source)
                .build()
        val persistedSecurity = insertRow(tenantId, userId, security)

        val trackCash = portfolioTracksCash(input.portfolioId)
        val cashKind = CashLegDerivation.cashKind(input.kind)
        val persistedCash =
            if (trackCash && cashKind != null && total.signum() != 0) {
                val cashAssetId = resolveCashAsset(tenantId, userId, input.currency)
                val cashLeg = CashLegDerivation.buildCashLeg(security, cashKind, cashAssetId, correlationId, total)
                insertRow(tenantId, userId, cashLeg)
            } else {
                null
            }
        return InsertedLegs(persistedSecurity, persistedCash)
    }

    /** Insert one transaction proto (security or cash leg) with a fresh id. */
    private fun insertRow(
        tenantId: String,
        userId: String,
        t: Transaction,
    ): Transaction {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
        TransactionsTable.insert {
            it[transactionId] = id.toString().toUuidColumn()
            it[TransactionsTable.tenantId] = tenantId.toUuidColumn()
            it[portfolioId] = t.portfolioId.toUuidColumn()
            it[assetId] = t.assetId.toUuidColumn()
            it[kind] = t.kind.toDbEnum("TX_")
            it[tradeDate] = t.tradeDate.toOffsetDateTime()
            it[settleDate] = if (t.hasSettleDate()) t.settleDate.toOffsetDateTime() else null
            it[quantity] = t.quantity.toDecimalOrZero()
            it[priceAmount] = t.price.amount.toDecimalOrZero()
            it[priceCurrency] = t.price.currency.ifBlank { null }
            it[feeAmount] = t.fee.amount.toDecimalOrZero()
            it[feeCurrency] = t.fee.currency.ifBlank { null }
            it[taxAmount] = t.tax.amount.toDecimalOrZero()
            it[taxCurrency] = t.tax.currency.ifBlank { null }
            it[totalAmount] = t.total.amount.toDecimalOrZero()
            it[totalCurrency] = t.total.currency.ifBlank { t.currency }
            it[currency] = t.currency
            it[externalId] = t.externalId.ifBlank { null }
            it[reversesTransactionId] = t.reversesTransactionId.ifBlank { null }?.toUuidColumn()
            it[correlationId] = t.correlationId.ifBlank { null }?.toUuidColumn()
            it[note] = t.note.ifBlank { null }
            it[txSource] = t.source.toDbEnum("TX_SRC_")
            it[recordedAt] = now
            it[recordedByUserId] = userId
        }
        return TransactionsTable
            .selectAll()
            .where { TransactionsTable.transactionId eq id.toString().toUuidColumn() }
            .first()
            .toTransaction()
    }

    private fun existingByExternalId(externalId: String): Transaction? =
        TransactionsTable
            .selectAll()
            .where { TransactionsTable.externalId eq externalId }
            .firstOrNull()
            ?.toTransaction()

    private fun portfolioTracksCash(portfolioId: String): Boolean =
        PortfoliosTable
            .selectAll()
            .where { PortfoliosTable.portfolioId eq portfolioId.toUuidColumn() }
            .firstOrNull()
            ?.get(PortfoliosTable.trackCash) ?: false

    /** Find or provision the per-(tenant,currency) ASSET_CASH instance. */
    private fun resolveCashAsset(
        tenantId: String,
        userId: String,
        currency: String,
    ): String {
        val symbol = "CASH.$currency"
        AssetsTable
            .selectAll()
            .where { (AssetsTable.symbol eq symbol) and (AssetsTable.kind eq "CASH") }
            .firstOrNull()
            ?.let { return it[AssetsTable.assetId].toUuidString() }

        val id = UUID.randomUUID()
        val now = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
        AssetsTable.insert {
            it[assetId] = id.toString().toUuidColumn()
            it[AssetsTable.tenantId] = tenantId.toUuidColumn()
            it[AssetsTable.symbol] = symbol
            it[name] = "Cash $currency"
            it[kind] = "CASH"
            it[AssetsTable.currency] = currency
            it[status] = "ACTIVE"
            it[createdAt] = now
            it[createdByUserId] = userId
            it[updatedAt] = now
            it[updatedByUserId] = userId
        }
        return id.toString()
    }

    @Suppress("ktlint:standard:function-naming")
    private fun ResultRow.toTransaction(): Transaction {
        val b =
            Transaction
                .newBuilder()
                .setTransactionId(this[TransactionsTable.transactionId].toUuidString())
                .setTenantId(this[TransactionsTable.tenantId].toUuidString())
                .setPortfolioId(this[TransactionsTable.portfolioId].toUuidString())
                .setAssetId(this[TransactionsTable.assetId].toUuidString())
                .setKind(dbEnum(this[TransactionsTable.kind], "TX_", TransactionKind::valueOf))
                .setTradeDate(this[TransactionsTable.tradeDate].toProtoTimestamp())
                .setQuantity(this[TransactionsTable.quantity].toDecimalString())
                .setPrice(money(this[TransactionsTable.priceAmount], this[TransactionsTable.priceCurrency]))
                .setFee(money(this[TransactionsTable.feeAmount], this[TransactionsTable.feeCurrency]))
                .setTax(money(this[TransactionsTable.taxAmount], this[TransactionsTable.taxCurrency]))
                .setTotal(money(this[TransactionsTable.totalAmount], this[TransactionsTable.totalCurrency]))
                .setCurrency(this[TransactionsTable.currency])
                .setSource(dbEnum(this[TransactionsTable.txSource], "TX_SRC_", TransactionSource::valueOf))
                .setRecordedAt(this[TransactionsTable.recordedAt].toProtoTimestamp())
                .setRecordedByUserId(this[TransactionsTable.recordedByUserId])
        this[TransactionsTable.settleDate]?.let { b.settleDate = it.toProtoTimestamp() }
        this[TransactionsTable.externalId]?.let { b.externalId = it }
        this[TransactionsTable.reversesTransactionId]?.let { b.reversesTransactionId = it.toUuidString() }
        this[TransactionsTable.correlationId]?.let { b.correlationId = it.toUuidString() }
        this[TransactionsTable.note]?.let { b.note = it }
        return b.build()
    }

    private fun money(
        amount: BigDecimal,
        currency: String?,
    ): Money =
        Money
            .newBuilder()
            .setAmount(amount.toDecimalString())
            .setCurrency(currency ?: "")
            .build()
}
