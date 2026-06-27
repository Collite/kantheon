package org.tatrman.kantheon.midas.core.calc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.common.v1.Severity
import java.time.Duration
import java.time.Instant

/**
 * Stage 3.6 T6 — the stale-FX signal boundary: a rate older than 24h yields a WARN
 * ResponseMessage; a fresh one yields null.
 */
class FxFreshnessSpec :
    StringSpec({

        val now = Instant.parse("2026-06-27T12:00:00Z")

        "a rate older than 24h yields a WARN message" {
            val msg = FxFreshness.staleWarning(now.minus(Duration.ofHours(30)), now, "USD/EUR")
            msg shouldNotBe null
            msg!!.severity shouldBe Severity.WARNING
            msg.code shouldBe "fx_rate_stale"
            msg.humanMessage shouldContain "USD/EUR"
        }

        "a rate within 24h yields no message" {
            FxFreshness.staleWarning(now.minus(Duration.ofHours(12)), now, "USD/EUR").shouldBeNull()
        }

        "exactly at the threshold is still fresh (boundary inclusive)" {
            FxFreshness.staleWarning(now.minus(Duration.ofHours(24)), now, "USD/EUR").shouldBeNull()
        }
    })
