package org.tatrman.kantheon.midas.core.derivation

import org.tatrman.kantheon.midas.core.infra.toDecimalOrZero
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource
import java.math.BigDecimal

/**
 * Derived cash legs (S2 — contracts §1.1.A). When a portfolio has `track_cash`,
 * each security/income transaction gets a counter-leg on the portfolio's cash
 * asset, sharing a `correlation_id`. The pure mapping (kind → cash direction,
 * total → cash amount, building the cash-leg proto) lives here; the repository
 * owns the DB side (ASSET_CASH provisioning + same-transaction insert).
 */
object CashLegDerivation {
    /** The cash counter-direction for a security leg, or null when none is derived. */
    fun cashKind(securityKind: TransactionKind): TransactionKind? =
        when (securityKind) {
            TransactionKind.TX_BUY,
            TransactionKind.TX_FEE,
            TransactionKind.TX_TAX,
            TransactionKind.TX_TRANSFER_OUT,
            -> TransactionKind.TX_CASH_DEBIT

            TransactionKind.TX_SELL,
            TransactionKind.TX_DIVIDEND,
            TransactionKind.TX_INTEREST,
            TransactionKind.TX_TRANSFER_IN,
            -> TransactionKind.TX_CASH_CREDIT

            // ADJUSTMENT and the cash legs themselves never derive a further leg.
            else -> null
        }

    /**
     * The sign a transaction kind contributes to a holding's *net position quantity*
     * — the single source of truth shared by the `mv_position_current` materialized
     * view (V0002) and [TransactionRepository.currentQuantity]. Storage convention:
     * `quantity` is a positive magnitude and the direction comes from `kind` (the one
     * exception is a reversal, which negates the magnitude and keeps the kind, so
     * `sign * negatedQuantity` cancels the original — consistent in both readers).
     *
     * Income/charge kinds (DIVIDEND/INTEREST/FEE/TAX) move cash, not the security
     * position, so they contribute `0` here; their cash effect rides the derived
     * CASH_CREDIT/CASH_DEBIT leg. **Keep this in lockstep with V0002's CASE.**
     */
    fun positionSign(kind: TransactionKind): Int =
        when (kind) {
            TransactionKind.TX_BUY,
            TransactionKind.TX_TRANSFER_IN,
            TransactionKind.TX_ADJUSTMENT,
            TransactionKind.TX_CASH_CREDIT,
            -> 1

            TransactionKind.TX_SELL,
            TransactionKind.TX_TRANSFER_OUT,
            TransactionKind.TX_CASH_DEBIT,
            -> -1

            else -> 0
        }

    /**
     * The cash magnitude of a security leg: its explicit `total` if provided, else
     * |quantity * price| + fee + tax (a v1 baseline; precise per-kind signing is a
     * Stage 3.3 calc concern).
     */
    fun cashAmount(security: Transaction): BigDecimal {
        val explicit = security.total.amount
        if (explicit.isNotBlank()) return explicit.toDecimalOrZero().abs()
        val qty = security.quantity.toDecimalOrZero().abs()
        val price =
            security.price.amount
                .toDecimalOrZero()
                .abs()
        val fee =
            security.fee.amount
                .toDecimalOrZero()
                .abs()
        val tax =
            security.tax.amount
                .toDecimalOrZero()
                .abs()
        return qty.multiply(price).add(fee).add(tax)
    }

    /**
     * Build the cash-leg proto for a persisted security leg. The repository supplies
     * the resolved `cashAssetId` (the per-(portfolio,currency) ASSET_CASH) and the
     * shared `correlationId`. The cash leg carries the cash amount as `quantity` and
     * `source = TX_SRC_DERIVATION`.
     */
    fun buildCashLeg(
        security: Transaction,
        cashKind: TransactionKind,
        cashAssetId: String,
        correlationId: String,
        amount: BigDecimal,
    ): Transaction {
        val money =
            Money
                .newBuilder()
                .setAmount(amount.toPlainString())
                .setCurrency(security.currency)
                .build()
        return Transaction
            .newBuilder()
            .setPortfolioId(security.portfolioId)
            .setAssetId(cashAssetId)
            .setKind(cashKind)
            .setTradeDate(security.tradeDate)
            .setQuantity(amount.toPlainString())
            .setTotal(money)
            .setCurrency(security.currency)
            .setCorrelationId(correlationId)
            .setSource(TransactionSource.TX_SRC_DERIVATION)
            .setNote("derived cash leg")
            .build()
    }
}
