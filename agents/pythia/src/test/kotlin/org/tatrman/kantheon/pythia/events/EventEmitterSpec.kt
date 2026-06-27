package org.tatrman.kantheon.pythia.events

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.orchestrator.ThrowingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.v1.Status
import java.util.UUID

/**
 * Stage 1.3 T3 — the EventEmitter appends to the PG log before publishing to NATS,
 * keeps the sequence monotone, fires the coarse lifecycle subject once per
 * transition, and degrades (logs, doesn't throw) when NATS is down.
 */
class EventEmitterSpec :
    StringSpec({

        "PG append precedes publish — the published frame carries its assigned sequence" {
            val events = InMemoryEventRepository()
            val nats = RecordingNatsPublisher()
            val emitter = EventEmitter(events, nats)
            val id = UUID.randomUUID()

            emitter.emit(id, Events.resolutionStarted("q0"))
            emitter.emit(id, Events.resolutionStarted("q1"))

            // Both events are in the PG log…
            events.replay(id, 0L) shouldHaveSize 2
            // …and the second published frame carries sequence 1 — only knowable after the
            // append assigned it, which proves the PG-append-before-publish ordering.
            val second =
                org.tatrman.kantheon.pythia.v1.InvestigationEvent
                    .parseFrom(nats.published[1].second)
            second.sequence shouldBe 1L
            second.investigationId shouldBe id.toString()
            nats.published[1].first shouldBe "pythia.investigation.$id.events"
        }

        "sequence is monotone across emits" {
            val events = InMemoryEventRepository()
            val emitter = EventEmitter(events, RecordingNatsPublisher())
            val id = UUID.randomUUID()
            val seqs = (1..3).map { emitter.emit(id, Events.resolutionStarted("q$it")) }
            seqs shouldContainExactly listOf(0L, 1L, 2L)
        }

        "lifecycle subject fires once per transition with the coarse status payload" {
            val nats = RecordingNatsPublisher()
            val emitter = EventEmitter(InMemoryEventRepository(), nats)
            val id = UUID.randomUUID()
            emitter.emitLifecycle(id, "u1", Status.STATUS_EXECUTING, Status.STATUS_SYNTHESIZING)
            nats.published.filter { it.first == "pythia.lifecycle.u1" } shouldHaveSize 1
        }

        "degrades to log-only when NATS throws — emit does not propagate, event still appended" {
            val events = InMemoryEventRepository()
            val emitter = EventEmitter(events, ThrowingNatsPublisher())
            val id = UUID.randomUUID()
            // must not throw
            emitter.emit(id, Events.resolutionStarted("q"))
            emitter.emitLifecycle(id, "u1", Status.STATUS_RESOLVING, Status.STATUS_PLANNING)
            events.replay(id, 0L) shouldHaveSize 1
        }

        "eventFromRow reconstructs the full event with sequence + investigation id" {
            val events = InMemoryEventRepository()
            val emitter = EventEmitter(events, RecordingNatsPublisher())
            val id = UUID.randomUUID()
            emitter.emit(id, Events.statusChanged(Status.STATUS_SUBMITTED, Status.STATUS_RESOLVING))
            val row = events.replay(id, 0L).single()
            val rebuilt = EventEmitter.eventFromRow(row)
            rebuilt.sequence shouldBe 0L
            rebuilt.investigationId shouldBe id.toString()
            rebuilt.statusChanged.to shouldBe Status.STATUS_RESOLVING
        }
    })
