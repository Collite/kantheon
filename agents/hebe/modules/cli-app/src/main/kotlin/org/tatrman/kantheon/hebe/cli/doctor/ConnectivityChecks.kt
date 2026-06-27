package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.config.Axes
import org.tatrman.kantheon.hebe.config.Availability

/**
 * Runtime connectivity / circuit-breaker doctor reflection (P2 Stage 2.5 T4).
 * Distinct from the static `platform.availability` axis — this reports the
 * breaker's state *now*. Gated on intermittent profiles (the only ones with a
 * breaker); an OPEN breaker is `DEGRADED` (Warn), never a hard FAIL — an offline
 * personal host is healthy, just disconnected.
 */
object ConnectivityChecks {
    fun isIntermittent(axes: Axes): Boolean = axes.platform.availability == Availability.INTERMITTENT

    fun specs(breakerOpen: suspend () -> Boolean): List<DoctorCheckSpec> =
        listOf(
            DoctorCheckSpec("Connectivity", ::isIntermittent) { _ ->
                if (breakerOpen()) {
                    CheckResult("Connectivity", CheckStatus.Warn, "degraded — circuit-breaker open, outbox holding")
                } else {
                    CheckResult("Connectivity", CheckStatus.Pass, "platform reachable")
                }
            },
        )
}
