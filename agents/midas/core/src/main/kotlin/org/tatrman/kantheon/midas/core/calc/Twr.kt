package org.tatrman.kantheon.midas.core.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * One valuation sub-period delimited by external cashflows: the portfolio's market
 * value at the start ([beginValue]) and end ([endValue]) of the period, both measured
 * *before* the boundary flow. A sub-period's return is `endValue / beginValue − 1`,
 * so the external flow at the boundary never pollutes the rate.
 */
data class SubPeriod(
    val beginValue: BigDecimal,
    val endValue: BigDecimal,
)

/**
 * Time-weighted return (TWR, Stage 3.2 T5 / 3.3) — the geometric link of the
 * sub-period returns, which eliminates the effect of external cash-flow timing:
 *
 *   TWR = ∏ (1 + r_i) − 1,   r_i = endValue_i / beginValue_i − 1.
 *
 * A sub-period with a zero (or negative) begin value contributes no factor (it cannot
 * define a return) and is skipped with the chain unbroken. Returned as a fraction
 * (`0.21` = 21%) rounded to [scale] decimals.
 */
object Twr {
    private val MC = MathContext.DECIMAL64

    fun linkedReturn(
        subPeriods: List<SubPeriod>,
        scale: Int = 6,
    ): BigDecimal {
        var product = BigDecimal.ONE
        var contributed = false
        for (p in subPeriods) {
            if (p.beginValue.signum() <= 0) continue
            val factor = p.endValue.divide(p.beginValue, MC)
            product = product.multiply(factor, MC)
            contributed = true
        }
        if (!contributed) return BigDecimal.ZERO.setScale(scale)
        return product.subtract(BigDecimal.ONE).setScale(scale, RoundingMode.HALF_UP)
    }
}
