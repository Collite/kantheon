package org.tatrman.kantheon.pythia.events

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.api.ProtoJson
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.EventRow
import org.tatrman.kantheon.pythia.v1.InvestigationEvent
import org.tatrman.kantheon.pythia.v1.InvestigationLifecycleEvent
import org.tatrman.kantheon.pythia.v1.Status
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The typed event channel (design §3.3, contracts §3). Every event is appended to
 * Postgres `pythia_events` (authoritative, sequence-assigned) **then** published
 * to NATS (`pythia.investigation.{id}.events`) for the live tail. On every status
 * transition a coarse [InvestigationLifecycleEvent] is also published to
 * `pythia.lifecycle.{user_id}` (PD-2). NATS failures degrade to log-only — the PG
 * log is never lost, only not-live (architecture §5/§7).
 */
class EventEmitter(
    private val events: EventRepository,
    private val nats: NatsPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(EventEmitter::class.java)

    /**
     * Append [event] (its `event` oneof set) to the PG log, then publish the
     * sequence-stamped event to NATS. Returns the assigned sequence. PG append
     * happens **before** the publish, so the published frame always carries its
     * final sequence.
     */
    fun emit(
        investigationId: UUID,
        event: InvestigationEvent,
    ): Long {
        val kind = event.eventCase.name
        // Persist the oneof payload (seq/emitted_at/investigation_id live in columns).
        val payload = ProtoJson.print(event)
        val seq = events.append(investigationId, kind, payload, clock.millis())
        val full =
            event
                .toBuilder()
                .setInvestigationId(investigationId.toString())
                .setSequence(seq)
                .setEmittedAt(nowIso())
                .build()
        publishQuietly("pythia.investigation.$investigationId.events", full.toByteArray())
        return seq
    }

    /** Publish the coarse status-only lifecycle event for the PD-2 inbox fan-out. */
    fun emitLifecycle(
        investigationId: UUID,
        userId: String,
        from: Status,
        to: Status,
    ) {
        val lifecycle =
            InvestigationLifecycleEvent
                .newBuilder()
                .setInvestigationId(investigationId.toString())
                .setUserId(userId)
                .setOldStatus(from)
                .setNewStatus(to)
                .setTs(nowIso())
                .build()
        publishQuietly("pythia.lifecycle.$userId", lifecycle.toByteArray())
    }

    private fun publishQuietly(
        subject: String,
        payload: ByteArray,
    ) {
        runCatching { nats.publish(subject, payload) }
            .onFailure { log.warn("nats publish to {} failed; degrading to PG-log-only", subject, it) }
    }

    private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(clock.instant())

    companion object {
        /** Reconstruct the full [InvestigationEvent] from a persisted row (SSE replay). */
        fun eventFromRow(row: EventRow): InvestigationEvent =
            ProtoJson
                .parseInto(row.payloadJson, InvestigationEvent.newBuilder())
                .setInvestigationId(row.investigationId.toString())
                .setSequence(row.sequence)
                .setEmittedAt(DateTimeFormatter.ISO_INSTANT.format(row.emittedAt))
                .build()
    }
}
