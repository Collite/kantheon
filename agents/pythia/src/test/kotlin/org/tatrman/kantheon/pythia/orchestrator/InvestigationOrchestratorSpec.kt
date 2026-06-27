package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.EventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.Status
import java.util.UUID

private class Fixture(
    scope: CoroutineScope,
) {
    val investigations: InvestigationRepository = InMemoryInvestigationRepository()
    val events: EventRepository = InMemoryEventRepository()
    val nats = RecordingNatsPublisher()
    val emitter = EventEmitter(events, nats)
    val checkpointer = Checkpointer(InMemoryCheckpointRepository(), investigations)
    val orchestrator =
        InvestigationOrchestrator(investigations, checkpointer, emitter, scope, awaitingTtlHours = 24)

    fun statusOf(id: UUID): Status = Status.valueOf(investigations.findById(id)!!.status)

    fun eventKinds(id: UUID): List<String> = events.replay(id, 0L).map { it.kind }
}

private fun request(
    policy: PlanApprovalPolicy,
    userId: String = "u1",
): Investigation =
    Investigation
        .newBuilder()
        .setId(UUID.randomUUID().toString())
        .setQuestion("why did revenue drop?")
        .setCaller(
            Caller
                .newBuilder()
                .setKind(Caller.Kind.IRIS)
                .setUserId(userId)
                .setTenantId("t1"),
        ).setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(policy))
        .build()

/**
 * Stage 1.3 T2 — the orchestrator drives the lifecycle over scripted stubs:
 * the AUTO path walks SUBMITTED → … → DONE; a plan-approval policy parks at
 * AWAITING_PLAN_APPROVAL and resumes idempotently.
 */
class InvestigationOrchestratorSpec :
    StringSpec({

        "AUTO policy walks SUBMITTED → … → DONE on stubs" {
            runTest {
                val f = Fixture(this)
                val id = f.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_AUTO))
                val terminal = f.orchestrator.awaitTerminal(id)
                terminal shouldBe Status.STATUS_DONE
                f.statusOf(id) shouldBe Status.STATUS_DONE

                // The event trace covers lifecycle → resolution → planning → execution → synthesis → conclusion.
                f.eventKinds(id) shouldContainInOrder
                    listOf(
                        "INVESTIGATION_SUBMITTED",
                        "RESOLUTION_STARTED",
                        "PLAN_DRAFTED",
                        "BATCH_LAUNCHED",
                        "STEP_COMPLETED",
                        "SYNTHESIZER_DONE",
                        "CONCLUSION",
                        "INVESTIGATION_DONE",
                    )
            }
        }

        "plan-approval policy parks at AWAITING_PLAN_APPROVAL and resumes on APPROVE" {
            runTest {
                val f = Fixture(this)
                val id = f.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_REQUIRED))
                advanceUntilIdle()
                f.statusOf(id) shouldBe Status.STATUS_AWAITING_PLAN_APPROVAL

                val outcome = f.orchestrator.approvePlan(id, approve = true)
                outcome shouldBe ResumeOutcome.Ok(Status.STATUS_EXECUTING)
                f.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE
            }
        }

        "double-resume of an already-resumed park is a Conflict (409 source)" {
            runTest {
                val f = Fixture(this)
                val id = f.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_REQUIRED))
                advanceUntilIdle()
                f.orchestrator.approvePlan(id, approve = true)
                advanceUntilIdle()
                // second approve loses the race — investigation already past the park
                val second = f.orchestrator.approvePlan(id, approve = true)
                (second is ResumeOutcome.Conflict) shouldBe true
            }
        }

        "halt from EXECUTING drains, synthesises partials, and terminates HALTED" {
            runTest {
                val f = Fixture(this)
                // seed an investigation parked-free at EXECUTING by submitting AUTO then halting mid-flight
                // is racy; instead drive a fresh one and halt before it completes is also racy — so assert
                // the halt of an EXECUTING-state record via a seeded record.
                val id = UUID.randomUUID()
                f.investigations.insert(
                    org.tatrman.kantheon.pythia.persistence.InvestigationRecord(
                        id = id,
                        callerJson = """{"userId":"u1"}""",
                        question = "q",
                        requestJson = "{}",
                        status = Status.STATUS_EXECUTING.name,
                        createdAt = java.time.Instant.now(),
                        updatedAt = java.time.Instant.now(),
                    ),
                )
                f.orchestrator.halt(id)
                f.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_HALTED
                val conclusion = f.investigations.findById(id)!!.conclusionJson!!
                (conclusion.contains("\"partial\":true") || conclusion.contains("STOP_USER")) shouldBe true
            }
        }
    })
