package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRecord
import org.tatrman.kantheon.pythia.v1.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Stage 1.3 T7 — the AWAITING_* TTL sweeper expires parked investigations past
 * their TTL to HALTED and leaves within-TTL ones alone (injected clock).
 */
class TtlSweeperSpec :
    StringSpec({

        val now = Instant.parse("2026-06-26T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        fun parked(
            ttlUntil: Instant,
            status: Status = Status.STATUS_AWAITING_USER_INPUT,
        ): InvestigationRecord =
            InvestigationRecord(
                id = UUID.randomUUID(),
                callerJson = """{"userId":"u1"}""",
                question = "q",
                requestJson = "{}",
                status = status.name,
                awaitingSince = now.minusSeconds(7200),
                awaitingTtlUntil = ttlUntil,
                createdAt = now.minusSeconds(7200),
                updatedAt = now.minusSeconds(7200),
            )

        "expires parks past TTL, leaves within-TTL ones" {
            val investigations = InMemoryInvestigationRepository()
            val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher(), clock)
            val sweeper = TtlSweeper(investigations, emitter, clock)

            val expired = parked(now.minusSeconds(60))
            val live = parked(now.plusSeconds(3600))
            investigations.insert(expired)
            investigations.insert(live)

            sweeper.sweep() shouldBe 1
            investigations.findById(expired.id)!!.status shouldBe Status.STATUS_HALTED.name
            investigations.findById(live.id)!!.status shouldBe Status.STATUS_AWAITING_USER_INPUT.name
        }

        "swept investigation carries a Rule-6 expiry warning and is finalised" {
            val investigations = InMemoryInvestigationRepository()
            val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher(), clock)
            val sweeper = TtlSweeper(investigations, emitter, clock)
            val expired = parked(now.minusSeconds(60))
            investigations.insert(expired)

            sweeper.sweep()

            val after = investigations.findById(expired.id)!!
            after.finalisedAt shouldBe now
            after.warningsJson.contains("expired awaiting input") shouldBe true
        }
    })
