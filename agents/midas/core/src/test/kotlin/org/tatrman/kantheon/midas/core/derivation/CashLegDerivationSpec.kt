package org.tatrman.kantheon.midas.core.derivation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.midas.v1.Money
import org.tatrman.kantheon.midas.v1.Transaction
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource
import java.math.BigDecimal

/** Stage 1.3 T7 — pure cash-leg derivation logic (§1.1.A), no DB. */
class CashLegDerivationSpec :
    StringSpec({

        "buys/fees/taxes/transfer-out debit cash; sells/income/transfer-in credit cash" {
            CashLegDerivation.cashKind(TransactionKind.TX_BUY) shouldBe TransactionKind.TX_CASH_DEBIT
            CashLegDerivation.cashKind(TransactionKind.TX_FEE) shouldBe TransactionKind.TX_CASH_DEBIT
            CashLegDerivation.cashKind(TransactionKind.TX_TAX) shouldBe TransactionKind.TX_CASH_DEBIT
            CashLegDerivation.cashKind(TransactionKind.TX_TRANSFER_OUT) shouldBe TransactionKind.TX_CASH_DEBIT
            CashLegDerivation.cashKind(TransactionKind.TX_SELL) shouldBe TransactionKind.TX_CASH_CREDIT
            CashLegDerivation.cashKind(TransactionKind.TX_DIVIDEND) shouldBe TransactionKind.TX_CASH_CREDIT
            CashLegDerivation.cashKind(TransactionKind.TX_INTEREST) shouldBe TransactionKind.TX_CASH_CREDIT
            CashLegDerivation.cashKind(TransactionKind.TX_TRANSFER_IN) shouldBe TransactionKind.TX_CASH_CREDIT
        }

        "adjustments and cash legs themselves derive no further leg" {
            CashLegDerivation.cashKind(TransactionKind.TX_ADJUSTMENT).shouldBeNull()
            CashLegDerivation.cashKind(TransactionKind.TX_CASH_DEBIT).shouldBeNull()
            CashLegDerivation.cashKind(TransactionKind.TX_CASH_CREDIT).shouldBeNull()
        }

        "cashAmount prefers the explicit total, else |qty*price| + fee + tax" {
            val computed =
                Transaction
                    .newBuilder()
                    .setQuantity("10")
                    .setPrice(Money.newBuilder().setAmount("150.00"))
                    .setFee(Money.newBuilder().setAmount("1.50"))
                    .setTax(Money.newBuilder().setAmount("0.50"))
                    .build()
            CashLegDerivation.cashAmount(computed) shouldBe BigDecimal("1502.00")

            val explicit = computed.toBuilder().setTotal(Money.newBuilder().setAmount("1234.56")).build()
            CashLegDerivation.cashAmount(explicit) shouldBe BigDecimal("1234.56")
        }

        "positionSign matches mv_position_current's kind→sign CASE" {
            // Increase the holding.
            CashLegDerivation.positionSign(TransactionKind.TX_BUY) shouldBe 1
            CashLegDerivation.positionSign(TransactionKind.TX_TRANSFER_IN) shouldBe 1
            CashLegDerivation.positionSign(TransactionKind.TX_ADJUSTMENT) shouldBe 1
            CashLegDerivation.positionSign(TransactionKind.TX_CASH_CREDIT) shouldBe 1
            // Decrease the holding.
            CashLegDerivation.positionSign(TransactionKind.TX_SELL) shouldBe -1
            CashLegDerivation.positionSign(TransactionKind.TX_TRANSFER_OUT) shouldBe -1
            CashLegDerivation.positionSign(TransactionKind.TX_CASH_DEBIT) shouldBe -1
            // Cash-only / income kinds do not move the security position quantity.
            CashLegDerivation.positionSign(TransactionKind.TX_DIVIDEND) shouldBe 0
            CashLegDerivation.positionSign(TransactionKind.TX_INTEREST) shouldBe 0
            CashLegDerivation.positionSign(TransactionKind.TX_FEE) shouldBe 0
            CashLegDerivation.positionSign(TransactionKind.TX_TAX) shouldBe 0
        }

        "buildCashLeg mirrors the security leg with the shared correlation + DERIVATION source" {
            val security =
                Transaction
                    .newBuilder()
                    .setPortfolioId("p1")
                    .setKind(TransactionKind.TX_BUY)
                    .setCurrency("USD")
                    .build()
            val leg =
                CashLegDerivation.buildCashLeg(
                    security,
                    TransactionKind.TX_CASH_DEBIT,
                    cashAssetId = "cash-asset",
                    correlationId = "corr-1",
                    amount = BigDecimal("1502.00"),
                )
            leg.kind shouldBe TransactionKind.TX_CASH_DEBIT
            leg.assetId shouldBe "cash-asset"
            leg.correlationId shouldBe "corr-1"
            leg.quantity shouldBe "1502.00"
            leg.currency shouldBe "USD"
            leg.source shouldBe TransactionSource.TX_SRC_DERIVATION
        }
    })
