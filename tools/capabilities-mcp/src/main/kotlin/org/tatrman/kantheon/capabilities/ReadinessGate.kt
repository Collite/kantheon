package org.tatrman.kantheon.capabilities

/**
 * Readiness flag flipped to `true` once the YAML manifest loader (Stage 1.4)
 * completes its initial scan. `/ready` returns 503 until then.
 */
class ReadinessGate {
    @Volatile
    var ready: Boolean = false
}
