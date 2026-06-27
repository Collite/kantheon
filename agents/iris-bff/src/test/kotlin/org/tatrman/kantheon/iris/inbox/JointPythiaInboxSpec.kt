package org.tatrman.kantheon.iris.inbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.pythia.v1.InvestigationEvent
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.StatusChanged
import org.tatrman.kantheon.pythia.v1.SynthesizerBlockCompleted

/**
 * Stage 5.2 T4 — joint: a chat-submitted SHALLOW investigation flows through the
 * Pythia client + the inbox aggregator + the event mapper. Submit returns an id; the
 * inbox lists it under its user-facing status; a lifecycle AWAITING transition renders
 * an envelope interaction; the conclusion renders as a pythia-attributed bubble. All
 * mocked (the live in-cluster Pythia→Iris e2e is the integration suite).
 */
class JointPythiaInboxSpec :
    StringSpec({

        "submit → inbox lists it (NEEDS_INPUT) → lifecycle + conclusion render through the mapper" {
            runTest {
                val summary =
                    InvestigationSummary(
                        id = "inv-1",
                        question = "Why did margin drop?",
                        status = "STATUS_AWAITING_USER_INPUT",
                        createdAt = "t0",
                        updatedAt = "t1",
                    )
                val pythia = FakePythiaClient(byUser = mapOf("u1" to listOf(summary)), submitId = "inv-1")

                // 1. Submit a SHALLOW investigation.
                val id = pythia.submit("""{"question":"Why did margin drop?","depthBudget":"SHALLOW"}""", "tok")
                id shouldBe "inv-1"
                pythia.submitted.single() shouldBe """{"question":"Why did margin drop?","depthBudget":"SHALLOW"}"""

                // 2. The inbox lists it under NEEDS_INPUT (the AWAITING_* bucket).
                val view = InboxAggregator.build(pythia.listInvestigations("u1", "tok")) { null }
                view.items.single().investigationId shouldBe "inv-1"
                view.items.single().status shouldBe UserFacingStatus.NEEDS_INPUT

                // 3. The lifecycle AWAITING transition renders an envelope interaction (a chip prompt).
                val mapper = PythiaEventMapper()
                val awaiting =
                    mapper
                        .toIris(
                            InvestigationEvent
                                .newBuilder()
                                .setInvestigationId("inv-1")
                                .setStatusChanged(
                                    StatusChanged
                                        .newBuilder()
                                        .setFrom(Status.STATUS_EXECUTING)
                                        .setTo(Status.STATUS_AWAITING_USER_INPUT),
                                ).build(),
                            "turn-1",
                        ).single()
                awaiting.eventCase shouldBe IrisStreamEvent.EventCase.ENVELOPE
                awaiting.envelope.pendingClarification.kind shouldBe "missing_arg"

                // 4. The conclusion renders as a pythia-attributed bubble.
                val block =
                    Block
                        .newBuilder()
                        .setBlockId(
                            "b0",
                        ).setRole(BlockRole.PRIMARY)
                        .setText("Margin fell 4%.")
                        .build()
                val bubble =
                    mapper
                        .toIris(
                            InvestigationEvent
                                .newBuilder()
                                .setInvestigationId("inv-1")
                                .setSynthesizerBlockCompleted(SynthesizerBlockCompleted.newBuilder().setBlock(block))
                                .build(),
                            "turn-1",
                        ).single()
                bubble.envelope.agentId shouldBe "pythia"
                bubble.envelope.text shouldBe "Margin fell 4%."
            }
        }
    })
