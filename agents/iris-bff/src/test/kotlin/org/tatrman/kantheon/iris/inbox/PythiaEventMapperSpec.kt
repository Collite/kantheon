package org.tatrman.kantheon.iris.inbox

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.pythia.v1.InvestigationDone
import org.tatrman.kantheon.pythia.v1.InvestigationEvent
import org.tatrman.kantheon.pythia.v1.StatusChanged
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.SynthesizerBlockCompleted

/**
 * Stage 5.2 T1/T2/T3 — the Pythia `InvestigationEvent` → `IrisStreamEvent` mapping:
 * lifecycle → step; an AWAITING_* transition → an envelope interaction
 * (PendingClarification + control chips); a synthesizer block → an envelope bubble
 * (`agent_id: pythia`); `investigation_done` → done.
 */
class PythiaEventMapperSpec :
    StringSpec({

        val mapper = PythiaEventMapper()

        fun statusChanged(
            from: Status,
            to: Status,
            inv: String = "inv-1",
        ) = InvestigationEvent
            .newBuilder()
            .setInvestigationId(inv)
            .setStatusChanged(StatusChanged.newBuilder().setFrom(from).setTo(to))
            .build()

        "a non-AWAITING status transition maps to a step event" {
            val out = mapper.toIris(statusChanged(Status.STATUS_PLANNING, Status.STATUS_EXECUTING), "t1")
            out.single().eventCase shouldBe IrisStreamEvent.EventCase.STEP
            out.single().step.summary shouldContain "EXECUTING"
        }

        "AWAITING_PLAN_APPROVAL maps to an envelope with a clarification + an approve/reject chip pair" {
            val out = mapper.toIris(statusChanged(Status.STATUS_PLANNING, Status.STATUS_AWAITING_PLAN_APPROVAL), "t1")
            val env = out.single().envelope
            env.agentId shouldBe "pythia"
            env.pendingClarification.kind shouldBe "plan_approval"
            env.chipsCount shouldBe 2
            env.getChips(0).prompt.prefilledArgsJson shouldContain "approve-plan"
            env.getChips(0).prompt.prefilledArgsJson shouldContain "inv-1"
        }

        "AWAITING_BUDGET_DECISION maps to the three budget chips (continue / halt / abandon)" {
            val out =
                mapper.toIris(
                    statusChanged(Status.STATUS_EXECUTING, Status.STATUS_AWAITING_BUDGET_DECISION),
                    "t1",
                )
            val env = out.single().envelope
            env.pendingClarification.kind shouldBe "budget_decision"
            env.chipsCount shouldBe 3
            env.chipsList.map { it.prompt.prefilledArgsJson }.joinToString() shouldContain "HALT_GRACEFULLY"
        }

        "AWAITING_USER_INPUT maps to an answer prompt" {
            val out = mapper.toIris(statusChanged(Status.STATUS_EXECUTING, Status.STATUS_AWAITING_USER_INPUT), "t1")
            out
                .single()
                .envelope.pendingClarification.kind shouldBe "missing_arg"
            out
                .single()
                .envelope
                .getChips(0)
                .prompt.prefilledArgsJson shouldContain "\"control\":\"answer\""
        }

        "a synthesizer block maps to an envelope bubble attributed to pythia (block-per-bubble)" {
            val block =
                Block
                    .newBuilder()
                    .setBlockId("b0")
                    .setRole(BlockRole.PRIMARY)
                    .setText("Found 23 customers.")
                    .build()
            val event =
                InvestigationEvent
                    .newBuilder()
                    .setInvestigationId("inv-1")
                    .setSynthesizerBlockCompleted(
                        SynthesizerBlockCompleted.newBuilder().setBlockIndex(0).setBlock(block),
                    ).build()
            val env = mapper.toIris(event, "t1").single().envelope
            env.agentId shouldBe "pythia"
            env.bubbleId shouldBe "b0"
            env.text shouldBe "Found 23 customers."
        }

        "investigation_done maps to a done event" {
            val event =
                InvestigationEvent
                    .newBuilder()
                    .setInvestigationId("inv-1")
                    .setInvestigationDone(InvestigationDone.newBuilder().setStatus(Status.STATUS_DONE))
                    .build()
            val out = mapper.toIris(event, "t1").single()
            out.eventCase shouldBe IrisStreamEvent.EventCase.DONE
            out.done.outcome shouldBe "done"
        }
    })
