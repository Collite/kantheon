package org.tatrman.kantheon.pythia.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

/**
 * Stage 1.2 T3 — repository behaviour pinned against the in-memory fakes (the
 * unit gate; real-PG fidelity = integration suite, planning-conventions §4). The
 * Exposed bindings mirror this behaviour. Covers: investigation insert/load +
 * JSONB survival, status update (save + compareAndSetStatus idempotency),
 * hypothesis/step upsert, handle insert incl. an inline PgResultSnapshot BYTEA
 * round-trip, and gap-free ordered event append.
 */
class RepositorySpec :
    StringSpec({

        fun record(
            id: UUID = UUID.randomUUID(),
            userId: String = "u1",
            status: String = "SUBMITTED",
        ): InvestigationRecord {
            val now = Instant.parse("2026-06-26T00:00:00Z")
            return InvestigationRecord(
                id = id,
                callerJson = """{"kind":"IRIS","userId":"$userId","tenantId":"t1"}""",
                question = "why did revenue drop?",
                requestJson = """{"id":"$id","question":"why did revenue drop?"}""",
                status = status,
                resolutionJson = """{"resolvedIntent":{"kind":"INTENT_RCA"}}""",
                planJson = """{"revision":0}""",
                createdAt = now,
                updatedAt = now,
            )
        }

        "investigation insert + load round-trips JSONB request/resolution/plan" {
            val repo = InMemoryInvestigationRepository()
            val rec = record()
            repo.insert(rec)
            val loaded = repo.findById(rec.id)
            loaded shouldNotBe null
            loaded!!.requestJson shouldBe rec.requestJson
            loaded.resolutionJson shouldBe rec.resolutionJson
            loaded.planJson shouldBe rec.planJson
        }

        "duplicate investigation id throws" {
            val repo = InMemoryInvestigationRepository()
            val rec = record()
            repo.insert(rec)
            try {
                repo.insert(rec)
                throw AssertionError("expected DuplicateInvestigationException")
            } catch (_: DuplicateInvestigationException) {
                // expected
            }
        }

        "compareAndSetStatus is idempotent — first signal wins, second affects nothing" {
            val repo = InMemoryInvestigationRepository()
            val rec =
                record(status = "AWAITING_PLAN_APPROVAL").copy(
                    awaitingSince = Instant.parse("2026-06-26T00:00:00Z"),
                    awaitingTtlUntil = Instant.parse("2026-06-27T00:00:00Z"),
                )
            repo.insert(rec)

            repo.compareAndSetStatus(rec.id, "AWAITING_PLAN_APPROVAL", "EXECUTING").shouldBeTrue()
            repo.compareAndSetStatus(rec.id, "AWAITING_PLAN_APPROVAL", "EXECUTING").shouldBeFalse()

            val after = repo.findById(rec.id)!!
            after.status shouldBe "EXECUTING"
            // leaving an AWAITING_* clears the awaiting columns
            after.awaitingSince.shouldBeNull()
            after.awaitingTtlUntil.shouldBeNull()
        }

        "list filters by user + status, newest first, with paging" {
            val repo = InMemoryInvestigationRepository()
            val base = Instant.parse("2026-06-26T00:00:00Z")
            val a = record(userId = "u1", status = "DONE").copy(createdAt = base, updatedAt = base)
            val b = record(userId = "u1", status = "EXECUTING").copy(createdAt = base.plusSeconds(10), updatedAt = base)
            val c = record(userId = "u2", status = "DONE").copy(createdAt = base.plusSeconds(20), updatedAt = base)
            listOf(a, b, c).forEach(repo::insert)

            val page0 = repo.list("u1", setOf("DONE", "EXECUTING"), page = 0, pageSize = 1)
            page0.rows shouldHaveSize 1
            page0.rows.first().id shouldBe b.id // newest first
            page0.nextPage shouldBe 1

            val page1 = repo.list("u1", setOf("DONE", "EXECUTING"), page = 1, pageSize = 1)
            page1.rows.single().id shouldBe a.id
            page1.nextPage.shouldBeNull()

            // u2's row is excluded from u1's inbox
            repo.list("u1", emptySet(), 0, 10).rows.map { it.id } shouldContainExactly listOf(b.id, a.id)
        }

        "findExpiredAwaiting returns only past-TTL parked investigations" {
            val repo = InMemoryInvestigationRepository()
            val now = Instant.parse("2026-06-26T12:00:00Z")
            val expired =
                record(status = "AWAITING_USER_INPUT").copy(
                    awaitingTtlUntil = now.minusSeconds(60),
                )
            val live =
                record(status = "AWAITING_USER_INPUT").copy(
                    awaitingTtlUntil = now.plusSeconds(3600),
                )
            val notAwaiting = record(status = "EXECUTING")
            listOf(expired, live, notAwaiting).forEach(repo::insert)

            val swept = repo.findExpiredAwaiting(now.toEpochMilli()).map { it.id }
            swept shouldContainExactly listOf(expired.id)
        }

        "hypothesis upsert is keyed (investigation_id, hyp_id)" {
            val repo = InMemoryHypothesisRepository()
            val inv = UUID.randomUUID()
            repo.upsert(
                HypothesisRecord(
                    inv,
                    "HB",
                    bodyJson = """{"confidence":0.5}""",
                    status = "UNDER_TEST",
                    confidence = 0.5,
                ),
            )
            repo.upsert(
                HypothesisRecord(
                    inv,
                    "HB",
                    bodyJson = """{"confidence":0.8}""",
                    status = "SUPPORTED",
                    confidence = 0.8,
                ),
            )
            val rows = repo.findByInvestigation(inv)
            rows shouldHaveSize 1
            rows.single().status shouldBe "SUPPORTED"
            rows.single().confidence shouldBe 0.8
        }

        "step upsert carries the output_handle" {
            val repo = InMemoryStepRepository()
            val inv = UUID.randomUUID()
            repo.upsert(StepRow(inv, "S1", "N1", bodyJson = """{"status":"RUNNING"}""", status = "RUNNING"))
            repo.upsert(
                StepRow(
                    inv,
                    "S1",
                    "N1",
                    bodyJson = """{"status":"COMPLETED"}""",
                    status = "COMPLETED",
                    outputHandleJson = """{"handleId":"h1","pgSnapshot":{"rowCount":23}}""",
                ),
            )
            val s = repo.findByInvestigation(inv).single()
            s.status shouldBe "COMPLETED"
            s.outputHandleJson shouldBe """{"handleId":"h1","pgSnapshot":{"rowCount":23}}"""
        }

        "handle insert round-trips an inline PgResultSnapshot BYTEA payload" {
            val repo = InMemoryHandleRepository()
            val inv = UUID.randomUUID()
            val arrow = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x00, 0x01)
            repo.insert(
                HandleRow(
                    investigationId = inv,
                    handleId = "h1",
                    kind = "pg_snapshot",
                    bodyJson = """{"handleId":"h1","pgSnapshot":{"rowCount":23}}""",
                    inlineData = arrow,
                ),
            )
            val loaded = repo.findById(inv, "h1")!!
            loaded.kind shouldBe "pg_snapshot"
            loaded.inlineData!!.contentEquals(arrow).shouldBeTrue()
        }

        "event append is gap-free, ordered, and per-investigation" {
            val repo = InMemoryEventRepository()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val s0 = repo.append(a, "investigation_submitted", "{}", 0L)
            val s1 = repo.append(a, "status_changed", "{}", 1L)
            val s2 = repo.append(a, "status_changed", "{}", 2L)
            val b0 = repo.append(b, "investigation_submitted", "{}", 0L)

            listOf(s0, s1, s2) shouldContainExactly listOf(0L, 1L, 2L)
            b0 shouldBe 0L // independent sequence per investigation

            repo.replay(a, 0L).map { it.sequence } shouldContainExactly listOf(0L, 1L, 2L)
            repo.replay(a, 2L).map { it.sequence } shouldContainExactly listOf(2L)
        }
    })
