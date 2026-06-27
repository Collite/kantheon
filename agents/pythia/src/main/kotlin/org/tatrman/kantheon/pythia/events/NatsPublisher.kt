package org.tatrman.kantheon.pythia.events

import org.slf4j.LoggerFactory

/**
 * The live-stream publisher seam. Pythia's authoritative store is Postgres
 * (`pythia_events`); NATS is the *live* tail. When NATS is down the system
 * degrades to PG-log-only — events are never lost, only not-live (architecture
 * §5/§7). Real JetStream wiring is integration-deferred (planning-conventions §4,
 * mirroring iris-bff's NATS posture); Phase 1 ships the abstraction + the
 * degrade-to-log default.
 */
interface NatsPublisher {
    /** Publish [payload] to [subject]; implementations must not throw on failure. */
    fun publish(
        subject: String,
        payload: ByteArray,
    )

    /** Whether a live NATS connection is currently up (drives the degrade gauge). */
    val connected: Boolean
}

/**
 * Degrade-to-log publisher — the Phase-1 default and the boot fallback when
 * `pythia.nats.url` is blank or unreachable. Logs at debug and reports
 * `connected = false`, so consumers fall back to the SSE PG-replay path.
 */
class LoggingNatsPublisher : NatsPublisher {
    private val log = LoggerFactory.getLogger(LoggingNatsPublisher::class.java)

    override val connected: Boolean = false

    override fun publish(
        subject: String,
        payload: ByteArray,
    ) {
        log.debug("nats degrade-to-log: would publish {} bytes to {}", payload.size, subject)
    }
}
