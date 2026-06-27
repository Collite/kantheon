package org.tatrman.kantheon.midas.core.calc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * TWR reference oracle (Stage 3.2 T5 / 3.3) — geometric linking of sub-period returns
 * at 4-decimal precision; external flows at sub-period boundaries must not pollute the rate.
 */
class TwrSpec :
    StringSpec({

        fun sp(
            begin: String,
            end: String,
        ) = SubPeriod(BigDecimal(begin), BigDecimal(end))

        fun BigDecimal.r4() = setScale(4, RoundingMode.HALF_UP)

        "two +10% sub-periods link geometrically to 0.2100 (a +500 flow between them is invisible)" {
            // 1.10 × 1.10 − 1 = 0.21
            Twr.linkedReturn(listOf(sp("1000", "1100"), sp("1600", "1760"))).r4() shouldBe BigDecimal("0.2100")
        }

        "a single sub-period returns its own rate" {
            Twr.linkedReturn(listOf(sp("1000", "1100"))).r4() shouldBe BigDecimal("0.1000")
        }

        "a −20% then +25% round-trip nets to 0.0000" {
            // 0.80 × 1.25 − 1 = 0
            Twr.linkedReturn(listOf(sp("1000", "800"), sp("800", "1000"))).r4() shouldBe BigDecimal("0.0000")
        }

        "a sub-period with a zero begin value is skipped, chain unbroken" {
            Twr.linkedReturn(listOf(sp("0", "500"), sp("1000", "1100"))).r4() shouldBe BigDecimal("0.1000")
        }

        "no sub-periods → 0" {
            Twr.linkedReturn(emptyList()).r4() shouldBe BigDecimal("0.0000")
        }
    })
