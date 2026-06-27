package org.tatrman.kantheon.midas.core.calc

import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import java.time.Duration
import java.time.Instant

/**
 * FX-rate staleness signal (Stage 3.6 T6). When a Midas-core MCP tool values in a base
 * currency using an FX rate older than [maxAge], it attaches the [staleWarning]
 * `ResponseMessage{severity=WARN}` (Rule 6) so the caller — and Iris's "stale" badge —
 * never treat a day-old rate as live. Pure + clock-injected so the boundary is unit-testable;
 * a fresh rate yields `null` (no message).
 */
object FxFreshness {
    val DEFAULT_MAX_AGE: Duration = Duration.ofHours(24)

    fun staleWarning(
        rateDate: Instant,
        now: Instant,
        pair: String,
        maxAge: Duration = DEFAULT_MAX_AGE,
    ): ResponseMessage? {
        val age = Duration.between(rateDate, now)
        if (age <= maxAge) return null
        val hours = age.toHours()
        return ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode("fx_rate_stale")
            .setHumanMessage(
                "FX rate for $pair is ${hours}h old (> ${maxAge.toHours()}h); valuation may be out of date",
            ).build()
    }
}
