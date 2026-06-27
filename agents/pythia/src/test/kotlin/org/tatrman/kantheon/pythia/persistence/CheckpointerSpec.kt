package org.tatrman.kantheon.pythia.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Stage 1.2 T5/T6 — the diff-based checkpointer (design §5, contracts §3a/§4).
 * Pins: snapshot on each of the three reasons; diff-based storage (second diff is
 * smaller than a full state); restore folds diffs back to equality; resume
 * idempotency (status-conditional, first-signal-wins); and the PD-5 per-handle
 * recipe + Arrow-fingerprint persist/restore.
 */
class CheckpointerSpec :
    StringSpec({

        fun seedInvestigation(
            repo: InvestigationRepository,
            status: String = "EXECUTING",
        ): UUID {
            val id = UUID.randomUUID()
            val now = Instant.parse("2026-06-26T00:00:00Z")
            repo.insert(
                InvestigationRecord(
                    id = id,
                    callerJson = """{"userId":"u1"}""",
                    question = "q",
                    requestJson = "{}",
                    status = status,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return id
        }

        fun checkpointer(): Pair<Checkpointer, Triple<UUID, InvestigationRepository, CheckpointRepository>> {
            val inv = InMemoryInvestigationRepository()
            val cps = InMemoryCheckpointRepository()
            val id = seedInvestigation(inv)
            return Checkpointer(cps, inv) to Triple(id, inv, cps)
        }

        "snapshots are taken under each of the three reasons" {
            val (ckpt, ctx) = checkpointer()
            val (id, _, cps) = ctx
            ckpt.checkpoint(id, CheckpointReason.AWAITING, SchedulerState(frontier = listOf("N1")))
            ckpt.checkpoint(id, CheckpointReason.PLAN_REVISED, SchedulerState(frontier = listOf("N1"), revision = 1))
            ckpt.checkpoint(id, CheckpointReason.BATCH_COMPLETED, SchedulerState(completedNodeIds = listOf("N1")))
            cps.loadAll(id).map { it.reason } shouldBe listOf("awaiting", "plan_revised", "batch_completed")
        }

        "storage is diff-based — a later checkpoint stores a smaller delta than the full state" {
            val (ckpt, ctx) = checkpointer()
            val (id, _, cps) = ctx
            val full = SchedulerState(frontier = listOf("N1", "N2"), inFlightStepIds = listOf("S1"), revision = 0)
            ckpt.checkpoint(id, CheckpointReason.BATCH_COMPLETED, full)
            // second checkpoint changes only one field
            ckpt.checkpoint(id, CheckpointReason.BATCH_COMPLETED, full.copy(revision = 1))

            val rows = cps.loadAll(id)
            val parse = { s: String -> Json.parseToJsonElement(s) as JsonObject }
            val firstDiffKeys = parse(rows[0].diffJson).keys
            val secondDiffKeys = parse(rows[1].diffJson).keys
            // the baseline diff carries every changed-from-default field; the second only `revision`
            secondDiffKeys shouldBe setOf("revision")
            secondDiffKeys.size shouldBeLessThan firstDiffKeys.size
        }

        "restore folds diffs back to the latest state" {
            val (ckpt, ctx) = checkpointer()
            val (id, _, _) = ctx
            val s1 = SchedulerState(frontier = listOf("N1"), budget = BudgetCounters(usd = 0.5, stepCount = 1))
            val s2 =
                s1.copy(
                    frontier = listOf("N2"),
                    completedNodeIds = listOf("N1"),
                    budget = BudgetCounters(usd = 1.2, stepCount = 2),
                )
            ckpt.checkpoint(id, CheckpointReason.BATCH_COMPLETED, s1)
            ckpt.checkpoint(id, CheckpointReason.BATCH_COMPLETED, s2)

            ckpt.restore(id) shouldBe s2
        }

        "restore of a never-checkpointed investigation is the default state" {
            val (ckpt, ctx) = checkpointer()
            val (id, _, _) = ctx
            // Different id with no checkpoints.
            ckpt.restore(UUID.randomUUID()) shouldBe SchedulerState()
            ckpt.restore(id) shouldBe SchedulerState()
        }

        "resume is idempotent — first tryResume wins, second loses" {
            val (ckpt, ctx) = checkpointer()
            val inv = InMemoryInvestigationRepository()
            val cps = InMemoryCheckpointRepository()
            val id = seedInvestigation(inv, status = "AWAITING_USER_INPUT")
            val c = Checkpointer(cps, inv)
            c.tryResume(id, "AWAITING_USER_INPUT", "EXECUTING").shouldBeTrue()
            c.tryResume(id, "AWAITING_USER_INPUT", "EXECUTING").shouldBeFalse()
            inv.findById(id)!!.status shouldBe "EXECUTING"
        }

        "PD-5 per-handle recipe + Arrow fingerprint persist and restore" {
            val (ckpt, ctx) = checkpointer()
            val (id, _, _) = ctx
            val state =
                SchedulerState(
                    handleRecipes =
                        mapOf(
                            "h1" to
                                HandleRecipe(
                                    recipeKind = "charon_move",
                                    recipeJson = """{"from":"worker_df","to":"seaweed"}""",
                                    arrowFingerprint = "sha256:abc123",
                                ),
                        ),
                )
            ckpt.checkpoint(id, CheckpointReason.AWAITING, state)
            val restored = ckpt.restore(id)
            restored.handleRecipes["h1"]!!.recipeKind shouldBe "charon_move"
            restored.handleRecipes["h1"]!!.arrowFingerprint shouldBe "sha256:abc123"
        }
    })
