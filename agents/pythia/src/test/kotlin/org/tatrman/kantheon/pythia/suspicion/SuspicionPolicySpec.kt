package org.tatrman.kantheon.pythia.suspicion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.orchestrator.RecordingNatsPublisher
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.v1.SuspicionPolicy
import java.util.UUID

/**
 * Stage 3.1 T4/T5 — `on_suspicious_result` policy actions + the suspicion/finding
 * events. A non-suspicious result is a no-op; a suspicious one emits the events and
 * maps CONTINUE / WARN / HALT.
 */
class SuspicionPolicySpec :
    StringSpec({

        fun harness(): Pair<SuspicionPolicyHandler, EventRepository> {
            val events = InMemoryEventRepository()
            return SuspicionPolicyHandler(EventEmitter(events, RecordingNatsPublisher())) to events
        }

        val id = UUID.randomUUID()
        val suspicious = SuspicionVerdict(true, listOf("empty result where rows expected"))

        "HALT policy returns HALT and emits suspicion_raised + finding" {
            val (handler, events) = harness()
            handler.apply(id, "step-N1", suspicious, SuspicionPolicy.SUSPICION_HALT) shouldBe SuspicionAction.HALT
            val kinds = events.replay(id, 0L).map { it.kind }
            kinds.contains("SUSPICION_RAISED") shouldBe true
            kinds.contains("FINDING") shouldBe true
        }

        "WARN policy returns WARN" {
            val (handler, _) = harness()
            handler.apply(id, "step-N1", suspicious, SuspicionPolicy.SUSPICION_WARN) shouldBe SuspicionAction.WARN
        }

        "CONTINUE policy returns CONTINUE" {
            val (handler, _) = harness()
            handler.apply(id, "step-N1", suspicious, SuspicionPolicy.SUSPICION_CONTINUE) shouldBe
                SuspicionAction.CONTINUE
        }

        "a non-suspicious result is a no-op (no events)" {
            val (handler, events) = harness()
            handler.apply(id, "step-N1", SuspicionVerdict(false, emptyList()), SuspicionPolicy.SUSPICION_HALT) shouldBe
                SuspicionAction.CONTINUE
            events.replay(id, 0L).shouldBeEmptyTrace()
        }
    })

private fun List<org.tatrman.kantheon.pythia.persistence.EventRow>.shouldBeEmptyTrace() {
    if (isNotEmpty()) throw AssertionError("expected no events, got ${map { it.kind }}")
}
