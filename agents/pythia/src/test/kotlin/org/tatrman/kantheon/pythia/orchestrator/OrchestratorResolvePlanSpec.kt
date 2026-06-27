package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.persistence.Checkpointer
import org.tatrman.kantheon.pythia.persistence.InMemoryCheckpointRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryEventRepository
import org.tatrman.kantheon.pythia.persistence.InMemoryInvestigationRepository
import org.tatrman.kantheon.pythia.persistence.InvestigationRepository
import org.tatrman.kantheon.pythia.plan.CapabilityChecker
import org.tatrman.kantheon.pythia.plan.PlanComposer
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.Planner
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.resolve.Resolver
import org.tatrman.kantheon.pythia.resolve.ThemisClient
import org.tatrman.kantheon.pythia.v1.Caller
import org.tatrman.kantheon.pythia.v1.HitlPolicy
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.PlanApprovalPolicy
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.themis.v1.Themis
import java.util.UUID

private const val VALID_PLAN =
    """{ "rationale": "r",
        "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
        "nodes": [
          { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "q.returns", "paramsJson": "{}" },
          { "nodeId": "N2", "kind": "render", "renderKind": "TABLE", "blockRole": "PRIMARY" } ],
        "edges": [ { "fromNodeId": "N1", "toNodeId": "N2", "binding": "N2.in = N1.out" } ] }"""

private fun fakeThemis(response: Themis.ResolveResponse) =
    object : ThemisClient {
        override suspend fun understand(
            request: Themis.ResolveRequest,
            bearer: String,
        ): Themis.ResolveResponse = response
    }

private val RESOLVED =
    Themis.ResolveResponse
        .newBuilder()
        .setResolution(Themis.Resolution.newBuilder().setIntentKind(Themis.IntentKind.PROCEDURAL))
        .build()

private val REFUSED =
    Themis.ResolveResponse
        .newBuilder()
        .setRefusal(
            Themis.RefusalWithGaps.newBuilder().addGaps(
                Themis.Gap
                    .newBuilder()
                    .setKind(Themis.GapKind.OUT_OF_DATA_SCOPE)
                    .setDescription("no data"),
            ),
        ).build()

private class Harness(
    scope: CoroutineScope,
    themis: Themis.ResolveResponse,
    planReplies: List<String>,
) {
    val investigations: InvestigationRepository = InMemoryInvestigationRepository()
    private val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
    private val checkpointer = Checkpointer(InMemoryCheckpointRepository(), investigations)
    private val resolver = Resolver(fakeThemis(themis))
    private val planner =
        Planner(PlanComposer(ScriptedPromptExecutor(planReplies)), PlanValidator(CapabilityChecker { true }))
    val orchestrator =
        InvestigationOrchestrator(
            investigations,
            checkpointer,
            emitter,
            scope,
            awaitingTtlHours = 24,
            resolver = resolver,
            planner = planner,
        )

    fun statusOf(id: UUID) = Status.valueOf(investigations.findById(id)!!.status)
}

private fun request(policy: PlanApprovalPolicy): Investigation =
    Investigation
        .newBuilder()
        .setQuestion("why did revenue drop?")
        .setCaller(Caller.newBuilder().setKind(Caller.Kind.IRIS).setUserId("u1"))
        .setHitlPolicy(HitlPolicy.newBuilder().setPlanApproval(policy))
        .build()

/**
 * Stage 2.1 — the orchestrator drives real resolution + planning: a resolved
 * investigation plans, parks for approval, and completes on APPROVE; a refusal
 * fails; an unplannable investigation HALTs (FAILED).
 */
class OrchestratorResolvePlanSpec :
    StringSpec({

        "resolve → plan → AWAITING_PLAN_APPROVAL → APPROVE → DONE" {
            runTest {
                val h = Harness(this, RESOLVED, listOf(VALID_PLAN))
                val id = h.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_REQUIRED), "tok")
                advanceUntilIdle()
                h.statusOf(id) shouldBe Status.STATUS_AWAITING_PLAN_APPROVAL
                // the plan was persisted by the real planner
                h.investigations
                    .findById(id)!!
                    .planJson!!
                    .contains("q.returns") shouldBe true

                h.orchestrator.approvePlan(id, approve = true)
                h.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE
            }
        }

        "AUTO policy resolves + plans + executes straight to DONE" {
            runTest {
                val h = Harness(this, RESOLVED, listOf(VALID_PLAN))
                val id = h.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_AUTO), "tok")
                h.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_DONE
            }
        }

        "a Themis refusal fails the investigation with the gaps as warnings" {
            runTest {
                val h = Harness(this, REFUSED, listOf(VALID_PLAN))
                val id = h.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_AUTO), "tok")
                h.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_FAILED
                h.investigations
                    .findById(id)!!
                    .warningsJson
                    .contains("OUT_OF_DATA_SCOPE") shouldBe true
            }
        }

        "an unplannable investigation (3 garbage replies) HALTs to FAILED" {
            runTest {
                val h = Harness(this, RESOLVED, listOf("nope", "nope", "nope"))
                val id = h.orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_AUTO), "tok")
                h.orchestrator.awaitTerminal(id) shouldBe Status.STATUS_FAILED
            }
        }

        "a mid-resolution bearer rejection parks AWAITING_RESOLUTION_INPUT (fail-closed-then-resume, not FAILED)" {
            runTest {
                val investigations: InvestigationRepository = InMemoryInvestigationRepository()
                val emitter = EventEmitter(InMemoryEventRepository(), RecordingNatsPublisher())
                val rejectingThemis =
                    object : ThemisClient {
                        override suspend fun understand(
                            request: Themis.ResolveRequest,
                            bearer: String,
                        ): Themis.ResolveResponse =
                            throw org.tatrman.kantheon.pythia.resolve
                                .ThemisAuthException("token expired")
                    }
                val orchestrator =
                    InvestigationOrchestrator(
                        investigations,
                        Checkpointer(InMemoryCheckpointRepository(), investigations),
                        emitter,
                        this,
                        awaitingTtlHours = 24,
                        resolver = Resolver(rejectingThemis),
                        planner =
                            Planner(
                                PlanComposer(ScriptedPromptExecutor(listOf(VALID_PLAN))),
                                PlanValidator(CapabilityChecker { true }),
                            ),
                    )
                val id = orchestrator.submit(request(PlanApprovalPolicy.PLAN_APPROVAL_AUTO), "stale-tok")
                advanceUntilIdle()
                Status.valueOf(investigations.findById(id)!!.status) shouldBe Status.STATUS_AWAITING_RESOLUTION_INPUT
            }
        }
    })
