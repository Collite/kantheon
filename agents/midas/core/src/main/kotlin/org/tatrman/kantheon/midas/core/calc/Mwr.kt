package org.tatrman.kantheon.midas.core.calc

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/** A dated external cashflow. Convention: contributions (money in) are NEGATIVE, the
 *  terminal portfolio value + distributions (money out / value returned) are POSITIVE. */
data class DatedCashflow(
    val date: LocalDate,
    val amount: BigDecimal,
)

/** Raised when the IRR solver cannot bracket / converge to a root (e.g. all-same-sign flows). */
class IrrNonConvergenceException(
    message: String,
) : RuntimeException(message)

/**
 * Money-weighted return (MWR) = the internal rate of return (IRR) of a portfolio's
 * dated external cashflows (Stage 3.3 T2). The annualised rate `r` that zeroes the NPV:
 *
 *   Σ cf_i · (1 + r)^(-t_i) = 0,   t_i = (date_i − date_0) / 365  (ACT/365 year fraction).
 *
 * Newton–Raphson from a sensible seed, with a **bisection fallback** when Newton steps
 * out of the valid domain or stalls — bisection always converges once the root is
 * bracketed, which guards the pathological / near-zero-derivative cases. Computed in
 * `Double` (IRR needs speed, not 30-digit precision) and returned as a `BigDecimal`
 * rounded to [scale] decimals. Sign convention: positive = gain.
 */
object Mwr {
    private const val TOLERANCE = 1e-10
    private const val MAX_ITERATIONS = 200
    private const val DAYS_PER_YEAR = 365.0

    fun irr(
        cashflows: List<DatedCashflow>,
        scale: Int = 6,
    ): BigDecimal {
        require(cashflows.size >= 2) { "IRR needs at least two cashflows" }
        val hasPositive = cashflows.any { it.amount.signum() > 0 }
        val hasNegative = cashflows.any { it.amount.signum() < 0 }
        if (!hasPositive || !hasNegative) {
            throw IrrNonConvergenceException("IRR requires both an inflow and an outflow; got all-same-sign cashflows")
        }
        val t0 = cashflows.minOf { it.date }
        val flows = cashflows.map { ChronoUnit.DAYS.between(t0, it.date) / DAYS_PER_YEAR to it.amount.toDouble() }

        val rate = newton(flows) ?: bisection(flows)
        return BigDecimal(rate).setScale(scale, RoundingMode.HALF_UP)
    }

    private fun npv(
        flows: List<Pair<Double, Double>>,
        rate: Double,
    ): Double = flows.sumOf { (t, cf) -> cf * (1.0 + rate).pow(-t) }

    private fun dNpv(
        flows: List<Pair<Double, Double>>,
        rate: Double,
    ): Double = flows.sumOf { (t, cf) -> -t * cf * (1.0 + rate).pow(-t - 1.0) }

    /** Newton–Raphson; returns null if it leaves the domain (rate ≤ −1) or stalls. */
    private fun newton(flows: List<Pair<Double, Double>>): Double? {
        var rate = 0.1
        repeat(MAX_ITERATIONS) {
            val f = npv(flows, rate)
            if (abs(f) < TOLERANCE) return rate
            val d = dNpv(flows, rate)
            if (abs(d) < TOLERANCE) return null // flat derivative → hand to bisection
            val next = rate - f / d
            if (next <= -1.0 || next.isNaN() || next.isInfinite()) return null
            rate = next
        }
        return null
    }

    /** Bisection on a bracketed root. Expands the upper bound until the sign flips, then halves. */
    private fun bisection(flows: List<Pair<Double, Double>>): Double {
        var lo = -0.9999
        var hi = 1.0
        var fLo = npv(flows, lo)
        var fHi = npv(flows, hi)
        var expand = 0
        while (fLo * fHi > 0 && expand < 80) {
            hi *= 2.0
            fHi = npv(flows, hi)
            expand++
        }
        if (fLo * fHi > 0) throw IrrNonConvergenceException("could not bracket an IRR root for the cashflows")
        repeat(MAX_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            val fMid = npv(flows, mid)
            if (abs(fMid) < TOLERANCE || (hi - lo) / 2.0 < TOLERANCE) return mid
            if (fLo * fMid <= 0) {
                hi = mid
                fHi = fMid
            } else {
                lo = mid
                fLo = fMid
            }
        }
        return (lo + hi) / 2.0
    }
}
