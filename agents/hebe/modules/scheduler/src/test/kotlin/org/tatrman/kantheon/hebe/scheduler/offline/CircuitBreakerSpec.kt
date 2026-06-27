package org.tatrman.kantheon.hebe.scheduler.offline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The runtime breaker state machine (P2 Stage 2.5 T4) — closed → open (after the
 * failure threshold) → half-open (after cooldown) → closed (on probe success).
 */
class CircuitBreakerSpec : StringSpec({

    "starts closed and allows requests" {
        val b = CircuitBreaker(failureThreshold = 3, cooldownMillis = 1000, now = { 0 })
        b.state() shouldBe BreakerState.CLOSED
        b.allowRequest() shouldBe true
    }

    "opens after the failure threshold and holds requests" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 3, cooldownMillis = 1000, now = { t })
        repeat(3) { b.recordFailure() }
        b.state() shouldBe BreakerState.OPEN
        b.allowRequest() shouldBe false
    }

    "moves to half-open after cooldown and allows a probe" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 1, cooldownMillis = 1000, now = { t })
        b.recordFailure()
        b.state() shouldBe BreakerState.OPEN
        t = 1500 // past cooldown
        b.state() shouldBe BreakerState.HALF_OPEN
        b.allowRequest() shouldBe true
    }

    "a successful half-open probe closes the breaker" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 1, cooldownMillis = 1000, now = { t })
        b.recordFailure()
        t = 1500
        b.state() shouldBe BreakerState.HALF_OPEN
        b.recordSuccess()
        b.state() shouldBe BreakerState.CLOSED
        b.allowRequest() shouldBe true
    }

    "a failed half-open probe re-opens with a fresh cooldown" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 1, cooldownMillis = 1000, now = { t })
        b.recordFailure()
        t = 1500
        b.state() shouldBe BreakerState.HALF_OPEN
        b.recordFailure()
        b.state() shouldBe BreakerState.OPEN
        b.allowRequest() shouldBe false
    }

    // (a) a straggler success must not slam a tripped breaker shut.
    "a success seen while OPEN does not close the breaker" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 3, cooldownMillis = 1000, now = { t })
        repeat(3) { b.recordFailure() }
        b.state() shouldBe BreakerState.OPEN
        // A straggler dispatched before the trip lands after it — must be a no-op.
        b.recordSuccess()
        b.state() shouldBe BreakerState.OPEN
        b.allowRequest() shouldBe false
        // The failure bookkeeping was not reset: it is still cooling down, and
        // after cooldown it offers a half-open probe (not a closed breaker).
        t = 1500
        b.state() shouldBe BreakerState.HALF_OPEN
    }

    // (b) HALF_OPEN admits exactly one concurrent caller (no thundering herd).
    "only one concurrent caller is admitted in half-open" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 1, cooldownMillis = 1000, now = { t })
        b.recordFailure()
        t = 1500
        b.state() shouldBe BreakerState.HALF_OPEN
        // First caller wins the single probe permit; everyone after is denied
        // until the probe resolves.
        b.allowRequest() shouldBe true
        b.allowRequest() shouldBe false
        b.allowRequest() shouldBe false
    }

    "one failed half-open probe re-opens regardless of the failure threshold" {
        var t = 0L
        // Threshold 5, yet a single half-open probe failure must re-open.
        val b = CircuitBreaker(failureThreshold = 5, cooldownMillis = 1000, now = { t })
        repeat(5) { b.recordFailure() }
        t = 1500
        b.state() shouldBe BreakerState.HALF_OPEN
        b.allowRequest() shouldBe true // the single probe
        b.recordFailure() // the probe fails
        b.state() shouldBe BreakerState.OPEN
        b.allowRequest() shouldBe false
        // The permit was released → a later cooldown can probe again.
        t = 3000
        b.state() shouldBe BreakerState.HALF_OPEN
        b.allowRequest() shouldBe true
    }

    "forceOpen degrades immediately" {
        val b = CircuitBreaker(now = { 0 })
        b.forceOpen()
        b.state() shouldBe BreakerState.OPEN
    }

    // (d) forceOpen is sticky: it never self-heals on the cooldown timer.
    "forceOpen stays open past the cooldown and only an explicit reset clears it" {
        var t = 0L
        val b = CircuitBreaker(failureThreshold = 3, cooldownMillis = 1000, now = { t })
        b.forceOpen()
        b.state() shouldBe BreakerState.OPEN
        // Well past cooldown — a forced-open breaker must not offer a probe.
        t = 100_000
        b.state() shouldBe BreakerState.OPEN
        b.allowRequest() shouldBe false
        // Only an explicit recovery clears it.
        b.reset()
        b.state() shouldBe BreakerState.CLOSED
        b.allowRequest() shouldBe true
    }
})
