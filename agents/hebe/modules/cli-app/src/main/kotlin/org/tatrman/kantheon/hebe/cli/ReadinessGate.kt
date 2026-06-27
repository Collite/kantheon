package org.tatrman.kantheon.hebe.cli

/**
 * The server-mode readiness gate (Hebe arc P3 S3.3 T4). `/ready` returns 200 only
 * when **config resolved + PG reachable + migrations at head + channels up**
 * (architecture §2.2 doctor parity); until then 503. `/healthz` (liveness) is the
 * bare process check, independent of these. Pure logic so each precondition's effect
 * on readiness is unit-testable; the HTTP wiring + the live probes mount on the
 * server-mode entrypoint (the Jib image), verified in the integration suite.
 */
class ReadinessGate {
    data class Checks(
        val configResolved: Boolean = false,
        val pgReachable: Boolean = false,
        val migrationsAtHead: Boolean = false,
        val channelsUp: Boolean = false,
    )

    fun ready(c: Checks): Boolean = c.configResolved && c.pgReachable && c.migrationsAtHead && c.channelsUp

    /** 200 once ready, else 503 (the conventional readiness-probe codes). */
    fun statusCode(c: Checks): Int = if (ready(c)) HTTP_OK else HTTP_UNAVAILABLE

    /** Per-precondition view for the `/ready` body (which dependency is blocking). */
    fun report(c: Checks): Map<String, Boolean> =
        mapOf(
            "config" to c.configResolved,
            "postgres" to c.pgReachable,
            "migrations" to c.migrationsAtHead,
            "channels" to c.channelsUp,
        )

    companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAVAILABLE = 503
    }
}
