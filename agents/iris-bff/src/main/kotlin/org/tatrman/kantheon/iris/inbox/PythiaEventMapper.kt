package org.tatrman.kantheon.iris.inbox

import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.Chip
import org.tatrman.kantheon.envelope.v1.ClarificationOption
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.PendingClarification
import org.tatrman.kantheon.envelope.v1.PromptChip
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.iris.v1.StepEvent
import org.tatrman.kantheon.pythia.v1.InvestigationEvent
import org.tatrman.kantheon.pythia.v1.Status

/**
 * Maps Pythia `InvestigationEvent`s (consumed from the SSE bridge — **not** NATS
 * directly, divergence 4) onto `IrisStreamEvent`s (Stage 5.2, iris/contracts):
 *   - lifecycle / hypothesis / batch / step events → `IrisStreamEvent.step` (detail_json),
 *   - an `AWAITING_*` transition → an **envelope interaction** (PendingClarification +
 *     PromptChips whose actions call back the Pythia control endpoints, T2),
 *   - synthesizer blocks → `IrisStreamEvent.envelope` (block-per-bubble, `agent_id:
 *     "pythia"`, executor refs on `Block.provenance` per PD-9, T3),
 *   - `investigation_done` → `IrisStreamEvent.done`.
 *
 * Pure — `IrisSse`/`PythiaInvestigationClient` assigns the monotone `sequence` and
 * pumps the events onto the iris stream; this keeps the mapping unit-testable.
 */
class PythiaEventMapper {
    fun toIris(
        event: InvestigationEvent,
        turnId: String,
    ): List<IrisStreamEvent> =
        when (event.eventCase) {
            InvestigationEvent.EventCase.STATUS_CHANGED -> statusChanged(event, turnId)
            InvestigationEvent.EventCase.SYNTHESIZER_BLOCK_COMPLETED ->
                listOf(envelope(turnId, event.synthesizerBlockCompleted.block))
            InvestigationEvent.EventCase.INVESTIGATION_DONE ->
                listOf(stream(turnId) { it.setDone(DoneEvent.newBuilder().setOutcome(doneOutcome(event))) })
            InvestigationEvent.EventCase.BATCH_LAUNCHED ->
                listOf(step(turnId, "batch", "started", event.batchLaunched.batchId))
            InvestigationEvent.EventCase.BATCH_COMPLETED ->
                listOf(step(turnId, "batch", "completed", event.batchCompleted.batchId))
            InvestigationEvent.EventCase.STEP_STARTED ->
                listOf(step(turnId, event.stepStarted.stepId, "started", event.stepStarted.summary))
            InvestigationEvent.EventCase.STEP_COMPLETED ->
                listOf(step(turnId, event.stepCompleted.stepId, "completed", "rows=${event.stepCompleted.rowCount}"))
            InvestigationEvent.EventCase.STEP_FAILED ->
                listOf(step(turnId, event.stepFailed.stepId, "failed", event.stepFailed.message))
            InvestigationEvent.EventCase.HYPOTHESIS_SUPPORTED ->
                listOf(step(turnId, "hypothesis:${event.hypothesisSupported.hypId}", "completed", "supported"))
            InvestigationEvent.EventCase.HYPOTHESIS_REFUTED ->
                listOf(step(turnId, "hypothesis:${event.hypothesisRefuted.hypId}", "completed", "refuted"))
            else -> emptyList()
        }

    private fun statusChanged(
        event: InvestigationEvent,
        turnId: String,
    ): List<IrisStreamEvent> {
        val to = event.statusChanged.to
        val invId = event.investigationId
        return if (to in AWAITING) {
            listOf(envelope(turnId, awaitingInteraction(to, invId)))
        } else {
            listOf(step(turnId, "status", "completed", "${event.statusChanged.from.name}→${to.name}"))
        }
    }

    /** The AWAITING_* pause rendered as an envelope interaction (PendingClarification + chips). */
    private fun awaitingInteraction(
        status: Status,
        investigationId: String,
    ): FormatEnvelope {
        val builder = FormatEnvelope.newBuilder().setAgentId("pythia")
        when (status) {
            Status.STATUS_AWAITING_PLAN_APPROVAL, Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL -> {
                val control = if (status == Status.STATUS_AWAITING_PLAN_APPROVAL) "approve-plan" else "approve-revision"
                builder
                    .setPendingClarification(clarification("plan_approval", investigationId, "Pythia drafted a plan."))
                    .addChips(controlChip("Approve plan", investigationId, control, """{"approve":true}"""))
                    .addChips(controlChip("Reject", investigationId, control, """{"approve":false}"""))
            }
            Status.STATUS_AWAITING_BUDGET_DECISION ->
                builder
                    .setPendingClarification(
                        clarification("budget_decision", investigationId, "Budget threshold reached."),
                    ).addChips(
                        controlChip("Continue", investigationId, "budget-decision", """{"decision":"CONTINUE"}"""),
                    ).addChips(
                        controlChip(
                            "Halt gracefully",
                            investigationId,
                            "budget-decision",
                            """{"decision":"HALT_GRACEFULLY"}""",
                        ),
                    ).addChips(controlChip("Abandon", investigationId, "budget-decision", """{"decision":"ABANDON"}"""))
            else ->
                // AWAITING_RESOLUTION_INPUT / AWAITING_USER_INPUT → an answer prompt.
                builder
                    .setPendingClarification(clarification("missing_arg", investigationId, "Pythia needs your input."))
                    .addChips(controlChip("Answer", investigationId, "answer", "{}"))
        }
        return builder.build()
    }

    private fun clarification(
        kind: String,
        resumeToken: String,
        contextText: String,
    ): PendingClarification =
        PendingClarification
            .newBuilder()
            .setKind(kind)
            .setResumeToken(resumeToken)
            .setContextText(contextText)
            .setIssuedByAgentId("pythia")
            .addOptions(ClarificationOption.newBuilder().setId(kind).setDisplay(contextText))
            .build()

    /** A chip whose action calls back a Pythia control endpoint (prefilled_args carry the call). */
    private fun controlChip(
        display: String,
        investigationId: String,
        control: String,
        argsJson: String,
    ): Chip =
        Chip
            .newBuilder()
            .setPrompt(
                PromptChip
                    .newBuilder()
                    .setDisplay(display)
                    .setPrompt(display)
                    .setSource("pattern_derived")
                    .setPrefilledArgsJson(
                        """{"agent":"pythia","investigationId":"$investigationId","control":"$control","args":$argsJson}""",
                    ),
            ).build()

    private fun envelope(
        turnId: String,
        env: FormatEnvelope,
    ): IrisStreamEvent = stream(turnId) { it.setEnvelope(env.toBuilder().setTurnId(turnId).setAgentId("pythia")) }

    /**
     * A synthesizer Block → a FormatEnvelope bubble. `agent_id = "pythia"` (PD-9
     * producing agent). The Block's PD-9 `provenance` (step / hypothesis / model refs)
     * is set Pythia-side and travels with the **artifact** (RenderableArtifact.blocks)
     * that backs the inbox hypothesis tree / drilldowns — FormatEnvelope itself has no
     * provenance field, so the live bubble carries the attribution + content only.
     */
    private fun envelope(
        turnId: String,
        block: Block,
    ): IrisStreamEvent =
        stream(turnId) {
            it.setEnvelope(
                FormatEnvelope
                    .newBuilder()
                    .setTurnId(turnId)
                    .setBubbleId(block.blockId)
                    .setAgentId("pythia")
                    .apply {
                        if (block.text.isNotBlank()) text = block.text
                        if (block.contentJson.isNotBlank()) contentJson = block.contentJson
                    }.setFormat(block.format),
            )
        }

    private fun step(
        turnId: String,
        node: String,
        phase: String,
        summary: String,
    ): IrisStreamEvent =
        stream(turnId) {
            it.setStep(
                StepEvent
                    .newBuilder()
                    .setNode(node)
                    .setPhase(phase)
                    .setSummary(summary),
            )
        }

    private fun stream(
        turnId: String,
        body: (IrisStreamEvent.Builder) -> IrisStreamEvent.Builder,
    ): IrisStreamEvent = body(IrisStreamEvent.newBuilder().setTurnId(turnId)).build()

    private fun doneOutcome(event: InvestigationEvent): String =
        when (event.investigationDone.status) {
            Status.STATUS_DONE -> "done"
            Status.STATUS_FAILED, Status.STATUS_INCONCLUSIVE -> "failed"
            else -> "done"
        }

    private companion object {
        val AWAITING =
            setOf(
                Status.STATUS_AWAITING_RESOLUTION_INPUT,
                Status.STATUS_AWAITING_PLAN_APPROVAL,
                Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL,
                Status.STATUS_AWAITING_USER_INPUT,
                Status.STATUS_AWAITING_BUDGET_DECISION,
            )
    }
}
