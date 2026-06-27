package org.tatrman.kantheon.hebe.scheduler.offline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime circuit-breaker (P2 Stage 2.5 T4; architecture §7.1) tracking whether a
 * platform dependency (llm-gateway, iris-bff) is reachable **now** — distinct
 * from the static `platform.availability` axis. Closed → calls flow; open →
 * outbox holds + `doctor` reports DEGRADED (not FAILED); after a cooldown a
 * single half-open probe is allowed, and its success closes the breaker.
 *
 * Concurrency invariants:
 *  - A success only closes the breaker from HALF_OPEN; a straggler success seen
 *    while OPEN is a no-op (it must not slam a tripped breaker shut).
 *  - Only one caller is admitted as the half-open probe; concurrent callers see
 *    OPEN and are denied until that probe resolves (no thundering herd).
 *  - A single failed half-open probe re-opens, independent of the closed-state
 *    failure threshold.
 *  - [forceOpen] is sticky: it does not self-heal on the cooldown timer; only an
 *    explicit [reset] (or a successful probe after [reset]) clears it.
 */
enum class BreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 30_000,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val state = AtomicReference(BreakerState.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)

    /** True while OPEN was entered via [forceOpen]; suppresses cooldown self-heal. */
    private val forced = AtomicBoolean(false)

    /** Single half-open probe permit: only the caller that wins it may probe. */
    private val probeInFlight = AtomicBoolean(false)

    /**
     * The effective state. Reports HALF_OPEN once the cooldown has elapsed
     * (the breaker is *willing* to probe), but observing the state never
     * consumes the single probe permit — that is [allowRequest]'s job.
     */
    fun state(): BreakerState {
        maybePromoteToHalfOpen()
        return state.get()
    }

    /**
     * Whether *this* caller should attempt a request now. CLOSED → always;
     * OPEN → never; HALF_OPEN → exactly one caller wins the single probe permit
     * and proceeds, the rest are denied until that probe resolves via
     * [recordSuccess]/[recordFailure]. No thundering herd on recovery.
     */
    fun allowRequest(): Boolean {
        maybePromoteToHalfOpen()
        return when (state.get()) {
            BreakerState.CLOSED -> true
            BreakerState.OPEN -> false
            // Admit only the caller that wins the single probe permit.
            BreakerState.HALF_OPEN -> probeInFlight.compareAndSet(false, true)
        }
    }

    fun recordSuccess() {
        when (state.get()) {
            BreakerState.HALF_OPEN -> {
                // The probe succeeded → close and clear the recovery bookkeeping.
                if (state.compareAndSet(BreakerState.HALF_OPEN, BreakerState.CLOSED)) {
                    consecutiveFailures.set(0)
                    forced.set(false)
                    probeInFlight.set(false)
                }
            }
            BreakerState.CLOSED -> consecutiveFailures.set(0)
            // A straggler success seen while OPEN must not reset state or counters.
            BreakerState.OPEN -> Unit
        }
    }

    fun recordFailure() {
        when (state.get()) {
            BreakerState.HALF_OPEN -> {
                // A single failed probe re-opens (independent of failureThreshold)
                // and releases the permit so a later cooldown can probe again.
                if (state.compareAndSet(BreakerState.HALF_OPEN, BreakerState.OPEN)) {
                    openedAt.set(now())
                    probeInFlight.set(false)
                }
            }
            else -> {
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= failureThreshold &&
                    state.getAndSet(BreakerState.OPEN) != BreakerState.OPEN
                ) {
                    openedAt.set(now())
                }
            }
        }
    }

    /** Force the breaker open as an explicit operator degrade — sticky (see class doc). */
    fun forceOpen() {
        forced.set(true)
        state.set(BreakerState.OPEN)
        openedAt.set(now())
        consecutiveFailures.set(failureThreshold)
        probeInFlight.set(false)
    }

    /** Explicit recovery: clear a forced/open breaker back to CLOSED. */
    fun reset() {
        consecutiveFailures.set(0)
        forced.set(false)
        probeInFlight.set(false)
        state.set(BreakerState.CLOSED)
    }

    /**
     * Move OPEN → HALF_OPEN once the cooldown has elapsed. Idempotent and
     * permit-free: it only reflects that the breaker is willing to be probed;
     * which single caller actually probes is decided by [allowRequest]. A
     * [forceOpen] breaker is sticky and never promoted on the timer.
     */
    private fun maybePromoteToHalfOpen() {
        if (state.get() == BreakerState.OPEN &&
            !forced.get() &&
            now() - openedAt.get() >= cooldownMillis
        ) {
            state.compareAndSet(BreakerState.OPEN, BreakerState.HALF_OPEN)
        }
    }
}
