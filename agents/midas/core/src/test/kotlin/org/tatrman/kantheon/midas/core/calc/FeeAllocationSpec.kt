package org.tatrman.kantheon.midas.core.calc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Pro-rata fee-allocation reference oracle (Stage 3.3 T5) — value-weighted shares that
 * sum back to the whole fee (rounding residue absorbed), with the zero-basis equal-split
 * fallback.
 */
class FeeAllocationSpec :
    StringSpec({

        fun pos(
            asset: String,
            value: String,
        ) = FeeBasis(asset, BigDecimal(value))

        fun List<FeeShare>.sum() = fold(BigDecimal.ZERO) { a, s -> a.add(s.allocatedFee) }

        fun BigDecimal.eq(s: String) = (compareTo(BigDecimal(s)) == 0)

        "value-weighted split: fee 100 over values 600/400 → 60 / 40" {
            val out = FeeAllocation.allocate(BigDecimal("100"), listOf(pos("A", "600"), pos("B", "400")))
            out.single { it.assetId == "A" }.allocatedFee.eq("60") shouldBe true
            out.single { it.assetId == "B" }.allocatedFee.eq("40") shouldBe true
            out.all { it.basis == "pro-rata-value" } shouldBe true
        }

        "rounding residue is absorbed so the parts sum to the whole fee" {
            // 100 / 3 = 33.3333 each → 99.9999; the 0.0001 residue lands on one share → sum 100.0000
            val out = FeeAllocation.allocate(BigDecimal("100"), listOf(pos("A", "1"), pos("B", "1"), pos("C", "1")))
            out.sum().eq("100") shouldBe true
        }

        "zero total basis falls back to an equal split" {
            val out = FeeAllocation.allocate(BigDecimal("90"), listOf(pos("A", "0"), pos("B", "0"), pos("C", "0")))
            out.all { it.allocatedFee.eq("30") } shouldBe true
            out.all { it.basis == "equal" } shouldBe true
        }

        "no positions → no allocations" {
            FeeAllocation.allocate(BigDecimal("100"), emptyList()) shouldBe emptyList()
        }
    })
