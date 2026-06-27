package org.tatrman.kantheon.golem.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Behavioural contract for [TurnsRepository], exercised against the in-memory
 * fake (real-PG fidelity is in the integration suite, planning-conventions §4).
 * `ExposedTurnsRepository` is held to the same invariants there.
 */
private fun turn(
    id: UUID = UUID.randomUUID(),
    requestId: UUID = UUID.randomUUID(),
    status: GolemTurnStatus = GolemTurnStatus.DONE,
    createdAt: Instant = Instant.parse("2026-06-18T10:00:00Z"),
    currentViewJson: String? = null,
    finalisedAt: Instant? = null,
) = GolemTurnRecord(
    id = id,
    requestId = requestId,
    golemId = "golem-erp",
    userId = "u1",
    tenantId = "default",
    question = "kolik mám tržeb?",
    resolvedIntentJson = """{"functionId":"acct.balance","confidence":0.9}""",
    planJson = """{"source":"PATTERN","confidence":0.95}""",
    envelopesJson = """[{"bubbleId":"b-1"}]""",
    currentViewJson = currentViewJson,
    pendingResumeToken = if (status == GolemTurnStatus.CLARIFICATION) "rt-1" else null,
    status = status,
    createdAt = createdAt,
    finalisedAt = finalisedAt,
)

class TurnsRepositorySpec :
    StringSpec({

        "insert then findById round-trips every field" {
            val repo = InMemoryTurnsRepository()
            val t =
                turn(
                    currentViewJson = """{"patternId":"acct.balance","bubbleId":"b-1"}""",
                    finalisedAt = Instant.parse("2026-06-18T10:00:01Z"),
                )
            repo.insert(t)
            repo.findById(t.id) shouldBe t
        }

        "findById on an unknown id is null" {
            InMemoryTurnsRepository().findById(UUID.randomUUID()).shouldBeNull()
        }

        "a duplicate id is rejected with DuplicateTurnException" {
            val repo = InMemoryTurnsRepository()
            val t = turn()
            repo.insert(t)
            shouldThrow<DuplicateTurnException> { repo.insert(t.copy(question = "different")) }
        }

        "findByRequestId breaks a same-timestamp tie deterministically by id" {
            val repo = InMemoryTurnsRepository()
            val req = UUID.randomUUID()
            val ts = Instant.parse("2026-06-19T10:00:00Z")
            val lo = turn(id = UUID.fromString("00000000-0000-0000-0000-000000000001"), requestId = req, createdAt = ts)
            val hi = turn(id = UUID.fromString("00000000-0000-0000-0000-0000000000ff"), requestId = req, createdAt = ts)
            repo.insert(lo)
            repo.insert(hi)
            // Same createdAt → the greater id wins, regardless of insert order.
            repo.findByRequestId(req) shouldBe hi
        }

        "findByRequestId returns the latest turn for a request (clarification then resume)" {
            val repo = InMemoryTurnsRepository()
            val req = UUID.randomUUID()
            val clarification =
                turn(
                    requestId = req,
                    status = GolemTurnStatus.CLARIFICATION,
                    createdAt = Instant.parse("2026-06-18T10:00:00Z"),
                )
            val resumed =
                turn(
                    requestId = req,
                    status = GolemTurnStatus.DONE,
                    createdAt = Instant.parse("2026-06-18T10:00:05Z"),
                )
            repo.insert(clarification)
            repo.insert(resumed)
            repo.findByRequestId(req) shouldBe resumed
        }

        "findByRequestId on an unknown request is null" {
            InMemoryTurnsRepository().findByRequestId(UUID.randomUUID()).shouldBeNull()
        }

        "GolemTurnStatus round-trips its wire value; unknown drift throws" {
            GolemTurnStatus.entries.forEach { GolemTurnStatus.fromWire(it.wire) shouldBe it }
            GolemTurnStatus.DONE.wire shouldBe "done"
            shouldThrow<IllegalStateException> { GolemTurnStatus.fromWire("archived") }
        }
    })
