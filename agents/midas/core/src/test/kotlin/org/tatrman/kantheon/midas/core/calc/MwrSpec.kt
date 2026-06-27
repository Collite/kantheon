package org.tatrman.kantheon.midas.core.calc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * MWR / IRR reference oracle (Stage 3.3 T1) — hand-computed annualised returns at
 * 4-decimal precision, plus the degenerate / non-convergence guards. Clean single-year
 * cases verify the exact rate; the multi-flow case verifies the defining property
 * (NPV ≈ 0 at the solved rate).
 */
class MwrSpec :
    StringSpec({

        val d0 = LocalDate.of(2026, 1, 1)

        fun cf(
            daysFromStart: Long,
            amount: String,
        ) = DatedCashflow(d0.plusDays(daysFromStart), BigDecimal(amount))

        fun BigDecimal.r4() = setScale(4, RoundingMode.HALF_UP)

        "one-year +10%: −1000 in, +1100 out → IRR 0.1000" {
            // 1100 / (1+r) = 1000 → r = 0.10
            Mwr.irr(listOf(cf(0, "-1000"), cf(365, "1100"))).r4() shouldBe BigDecimal("0.1000")
        }

        "one-year −10%: −1000 in, +900 out → IRR −0.1000" {
            Mwr.irr(listOf(cf(0, "-1000"), cf(365, "900"))).r4() shouldBe BigDecimal("-0.1000")
        }

        "near-zero return is stable: −1000 in, +1000 out → IRR 0.0000" {
            Mwr.irr(listOf(cf(0, "-1000"), cf(365, "1000"))).r4() shouldBe BigDecimal("0.0000")
        }

        "multi-flow with an interim contribution solves to NPV ≈ 0 at the returned rate" {
            // −1000 @ d0, −500 @ d182, +1600 @ d365 — no clean closed form; assert the IRR property.
            val flows = listOf(cf(0, "-1000"), cf(182, "-500"), cf(365, "1600"))
            val rate = Mwr.irr(flows).toDouble()
            val npv =
                flows.sumOf { f ->
                    val t = ChronoUnit.DAYS.between(d0, f.date) / 365.0
                    f.amount.toDouble() * (1.0 + rate).pow(-t)
                }
            // tolerance reflects the 6-dp rate rounding: a ~1e-6 rate error on ~1600 of flows
            // leaves a sub-cent NPV residual, which is "≈ 0" for a money-weighted return.
            (abs(npv) < 1e-2) shouldBe true
        }

        "fewer than two cashflows is rejected" {
            shouldThrow<IllegalArgumentException> { Mwr.irr(listOf(cf(0, "-1000"))) }
        }

        "all-same-sign cashflows have no IRR (non-convergence guard, no hang)" {
            shouldThrow<IrrNonConvergenceException> { Mwr.irr(listOf(cf(0, "1000"), cf(365, "500"))) }
        }
    })
