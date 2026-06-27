package org.tatrman.kantheon.midas.core.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A position the fee is allocated across — its market value is the pro-rata basis. */
data class FeeBasis(
    val assetId: String,
    val marketValue: BigDecimal,
)

/** One asset's share of an allocated fee. */
data class FeeShare(
    val assetId: String,
    val allocatedFee: BigDecimal,
    val basis: String,
)

/**
 * Pro-rata fee allocation (Stage 3.3 T5) — spreads a transaction's total fee across
 * positions in proportion to their market value: `share_i = fee · value_i / Σ value`.
 * The shares are rounded to [scale] decimals and the **rounding residue is added to
 * the largest share** so `Σ share_i == fee` exactly (no penny lost/created). When the
 * total basis is zero (no valued positions) the fee is split **equally** instead — the
 * pro-rata basis is undefined, so equal is the defensible fallback.
 */
object FeeAllocation {
    private val MC = MathContext.DECIMAL64
    private const val EQUAL = "equal"
    private const val PRO_RATA = "pro-rata-value"

    fun allocate(
        totalFee: BigDecimal,
        positions: List<FeeBasis>,
        scale: Int = 4,
    ): List<FeeShare> {
        if (positions.isEmpty()) return emptyList()
        val totalValue = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.marketValue) }

        val raw =
            if (totalValue.signum() <= 0) {
                val even = totalFee.divide(BigDecimal(positions.size), MC)
                positions.map { FeeShare(it.assetId, even.setScale(scale, RoundingMode.HALF_UP), EQUAL) }
            } else {
                positions.map {
                    val share = totalFee.multiply(it.marketValue, MC).divide(totalValue, MC)
                    FeeShare(it.assetId, share.setScale(scale, RoundingMode.HALF_UP), PRO_RATA)
                }
            }
        return balance(raw, totalFee.setScale(scale, RoundingMode.HALF_UP))
    }

    /** Push the rounding residue onto the largest share so the parts sum to the whole fee. */
    private fun balance(
        shares: List<FeeShare>,
        target: BigDecimal,
    ): List<FeeShare> {
        val sum = shares.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.allocatedFee) }
        val residue = target.subtract(sum)
        if (residue.signum() == 0) return shares
        val maxIdx = shares.indices.maxByOrNull { shares[it].allocatedFee }!!
        return shares.mapIndexed { i, s ->
            if (i == maxIdx) s.copy(allocatedFee = s.allocatedFee.add(residue)) else s
        }
    }
}
