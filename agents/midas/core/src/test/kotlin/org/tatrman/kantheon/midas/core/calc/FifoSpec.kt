package org.tatrman.kantheon.midas.core.calc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

/**
 * FIFO cost-basis reference oracle (Stage 3.3 T3) — multi-lot consumption order,
 * remaining quantities, realised P&L, and the **SELL-reversal** interaction (reversing
 * a sale releases the exact lots it consumed and backs out its realised P&L).
 */
class FifoSpec :
    StringSpec({

        val t0 = Instant.parse("2026-01-01T00:00:00Z")

        fun buy(
            id: String,
            day: Long,
            qty: String,
            price: String,
        ) = LotEvent(id, t0.plusSeconds(day * 86_400), LotEvent.Kind.BUY, BigDecimal(qty), BigDecimal(price))

        fun sell(
            id: String,
            day: Long,
            qty: String,
            price: String,
        ) = LotEvent(id, t0.plusSeconds(day * 86_400), LotEvent.Kind.SELL, BigDecimal(qty), BigDecimal(price))

        fun reverseSell(
            id: String,
            day: Long,
            reverses: String,
            qty: String,
            price: String,
        ) = LotEvent(id, t0.plusSeconds(day * 86_400), LotEvent.Kind.SELL, BigDecimal(qty), BigDecimal(price), reverses)

        fun BigDecimal.eq(s: String) = (compareTo(BigDecimal(s)) == 0)

        "multi-buy + partial sell consumes FIFO: realised 650, one remaining lot of 50 @ 12" {
            // buy 100@10, buy 100@12, sell 150@15 → consume 100@10 + 50@12
            // realised = 100*(15−10) + 50*(15−12) = 500 + 150 = 650; remaining 50 @ 12 (cost 600)
            val r =
                Fifo.costBasis(
                    listOf(buy("b1", 0, "100", "10"), buy("b2", 1, "100", "12"), sell("s1", 2, "150", "15")),
                )
            r.realisedPnl.eq("650") shouldBe true
            r.openLots shouldHaveSize 1
            r.openLots[0].remainingQuantity.eq("50") shouldBe true
            r.openLots[0].costPerUnit.eq("12") shouldBe true
            r.openLots[0].totalCost.eq("600") shouldBe true
            r.openLots[0].sourceTransactionId shouldBe "b2"
        }

        "reversing the sell releases the consumed lots and backs out the realised P&L" {
            val r =
                Fifo.costBasis(
                    listOf(
                        buy("b1", 0, "100", "10"),
                        buy("b2", 1, "100", "12"),
                        sell("s1", 2, "150", "15"),
                        reverseSell("rev", 3, "s1", "-150", "15"),
                    ),
                )
            r.realisedPnl.eq("0") shouldBe true
            r.openLots shouldHaveSize 2
            // restored to the original FIFO order: 100 @ 10 (b1) then 100 @ 12 (b2)
            r.openLots[0].sourceTransactionId shouldBe "b1"
            r.openLots[0].remainingQuantity.eq("100") shouldBe true
            r.openLots[1].sourceTransactionId shouldBe "b2"
            r.openLots[1].remainingQuantity.eq("100") shouldBe true
        }

        "partial sell of a single lot leaves the remainder" {
            val r = Fifo.costBasis(listOf(buy("b1", 0, "100", "10"), sell("s1", 1, "30", "20")))
            r.realisedPnl.eq("300") shouldBe true // 30 * (20 − 10)
            r.openLots shouldHaveSize 1
            r.openLots[0].remainingQuantity.eq("70") shouldBe true
        }

        "reversing a PARTIAL sell merges the released qty back into the still-open front lot" {
            // sell 30 of the 100-lot, then reverse it → the lot is whole again, P&L backed out
            val r =
                Fifo.costBasis(
                    listOf(
                        buy("b1", 0, "100", "10"),
                        sell("s1", 1, "30", "20"),
                        reverseSell("rev", 2, "s1", "-30", "20"),
                    ),
                )
            r.realisedPnl.eq("0") shouldBe true
            r.openLots shouldHaveSize 1 // merged, not a duplicate front lot
            r.openLots[0].sourceTransactionId shouldBe "b1"
            r.openLots[0].remainingQuantity.eq("100") shouldBe true
        }

        "reversing a BUY drops its still-open lot" {
            // a BUY reversal carries no recorded consumption → the open lot it created is removed
            val reverseBuy =
                LotEvent(
                    "rev",
                    t0.plusSeconds(2 * 86_400),
                    LotEvent.Kind.BUY,
                    BigDecimal("100"),
                    BigDecimal("10"),
                    "b1",
                )
            val r = Fifo.costBasis(listOf(buy("b1", 0, "100", "10"), buy("b2", 1, "50", "12"), reverseBuy))
            r.openLots shouldHaveSize 1
            r.openLots[0].sourceTransactionId shouldBe "b2"
            r.openLots[0].remainingQuantity.eq("50") shouldBe true
        }
    })
