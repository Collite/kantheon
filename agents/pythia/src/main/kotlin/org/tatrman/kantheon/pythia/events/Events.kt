package org.tatrman.kantheon.pythia.events

import org.tatrman.kantheon.pythia.v1.BatchCompleted
import org.tatrman.kantheon.pythia.v1.BatchLaunched
import org.tatrman.kantheon.pythia.v1.BudgetExhausted
import org.tatrman.kantheon.pythia.v1.BudgetThreshold
import org.tatrman.kantheon.pythia.v1.Conclusion
import org.tatrman.kantheon.pythia.v1.ConclusionReached
import org.tatrman.kantheon.pythia.v1.DeepeningDecision
import org.tatrman.kantheon.pythia.v1.Finding
import org.tatrman.kantheon.pythia.v1.FindingRecorded
import org.tatrman.kantheon.pythia.v1.HypothesesPrioritized
import org.tatrman.kantheon.pythia.v1.HypothesisInconclusive
import org.tatrman.kantheon.pythia.v1.HypothesisRefuted
import org.tatrman.kantheon.pythia.v1.HypothesisSupported
import org.tatrman.kantheon.pythia.v1.HypothesisUnderTest
import org.tatrman.kantheon.pythia.v1.Investigation
import org.tatrman.kantheon.pythia.v1.InvestigationDone
import org.tatrman.kantheon.pythia.v1.InvestigationEvent
import org.tatrman.kantheon.pythia.v1.InvestigationSubmitted
import org.tatrman.kantheon.pythia.v1.LooseEnd
import org.tatrman.kantheon.pythia.v1.LooseEndDeclared
import org.tatrman.kantheon.pythia.v1.LooseEndGenerated
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanDrafted
import org.tatrman.kantheon.pythia.v1.PlanRevised
import org.tatrman.kantheon.pythia.v1.PrioritizedHyp
import org.tatrman.kantheon.pythia.v1.ResolutionStarted
import org.tatrman.kantheon.pythia.v1.RevisionKind
import org.tatrman.kantheon.pythia.v1.ResourceUsage
import org.tatrman.kantheon.pythia.v1.SchedulerDrained
import org.tatrman.kantheon.pythia.v1.Status
import org.tatrman.kantheon.pythia.v1.StatusChanged
import org.tatrman.kantheon.pythia.v1.StepCompleted
import org.tatrman.kantheon.pythia.v1.StepFailed
import org.tatrman.kantheon.pythia.v1.StepRetrying
import org.tatrman.kantheon.pythia.v1.StepStarted
import org.tatrman.kantheon.pythia.v1.SuspicionRaised
import org.tatrman.kantheon.pythia.v1.SynthesizerBlockCompleted
import org.tatrman.kantheon.pythia.v1.SynthesizerBlockStarted
import org.tatrman.kantheon.pythia.v1.SynthesizerDone

/** Factory helpers for the [InvestigationEvent] oneof (design §3.3 vocabulary). */
object Events {
    fun submitted(request: Investigation): InvestigationEvent =
        event { setInvestigationSubmitted(InvestigationSubmitted.newBuilder().setRequest(request)) }

    fun statusChanged(
        from: Status,
        to: Status,
        reason: String? = null,
    ): InvestigationEvent =
        event {
            setStatusChanged(
                StatusChanged
                    .newBuilder()
                    .setFrom(from)
                    .setTo(to)
                    .apply { reason?.let { setReason(it) } },
            )
        }

    fun resolutionStarted(question: String): InvestigationEvent =
        event { setResolutionStarted(ResolutionStarted.newBuilder().setQuestion(question)) }

    fun planDrafted(
        plan: PlanDag,
        awaitingApproval: Boolean,
    ): InvestigationEvent =
        event { setPlanDrafted(PlanDrafted.newBuilder().setPlan(plan).setAwaitingApproval(awaitingApproval)) }

    fun batchLaunched(
        batchId: String,
        stepIds: List<String>,
        projectedCostUsd: Double,
        maxParallelism: Int,
    ): InvestigationEvent =
        event {
            setBatchLaunched(
                BatchLaunched
                    .newBuilder()
                    .setBatchId(batchId)
                    .addAllStepIds(stepIds)
                    .setProjectedCostUsd(projectedCostUsd)
                    .setMaxParallelism(maxParallelism),
            )
        }

    fun stepStarted(
        stepId: String,
        nodeKind: String,
        summary: String,
    ): InvestigationEvent =
        event {
            setStepStarted(
                StepStarted
                    .newBuilder()
                    .setStepId(stepId)
                    .setNodeKind(nodeKind)
                    .setSummary(summary),
            )
        }

    fun stepCompleted(
        stepId: String,
        outputHandleId: String,
        rowCount: Long,
    ): InvestigationEvent =
        event {
            setStepCompleted(
                StepCompleted
                    .newBuilder()
                    .setStepId(stepId)
                    .setOutputHandleId(outputHandleId)
                    .setRowCount(rowCount),
            )
        }

    fun batchCompleted(
        batchId: String,
        succeeded: List<String>,
        actualCostUsd: Double,
    ): InvestigationEvent =
        event {
            setBatchCompleted(
                BatchCompleted
                    .newBuilder()
                    .setBatchId(
                        batchId,
                    ).addAllSucceeded(succeeded)
                    .setActualCostUsd(actualCostUsd),
            )
        }

    fun stepRetrying(
        stepId: String,
        attempt: Int,
        reason: String,
    ): InvestigationEvent =
        event {
            setStepRetrying(
                StepRetrying
                    .newBuilder()
                    .setStepId(stepId)
                    .setAttempt(attempt)
                    .setReason(reason),
            )
        }

    fun stepFailed(
        stepId: String,
        errorCode: String,
        message: String,
        recoverable: Boolean,
    ): InvestigationEvent =
        event {
            setStepFailed(
                StepFailed
                    .newBuilder()
                    .setStepId(stepId)
                    .setErrorCode(errorCode)
                    .setMessage(message)
                    .setRecoverable(recoverable),
            )
        }

    fun hypothesisUnderTest(
        hypId: String,
        stepIds: List<String>,
    ): InvestigationEvent =
        event { setHypothesisUnderTest(HypothesisUnderTest.newBuilder().setHypId(hypId).addAllStepIds(stepIds)) }

    fun hypothesisSupported(
        hypId: String,
        evidenceStepIds: List<String>,
        confidence: Double,
    ): InvestigationEvent =
        event {
            setHypothesisSupported(
                HypothesisSupported
                    .newBuilder()
                    .setHypId(hypId)
                    .addAllEvidenceStepIds(evidenceStepIds)
                    .setConfidence(confidence),
            )
        }

    fun hypothesisRefuted(
        hypId: String,
        refutingStepId: String,
        reasoning: String,
    ): InvestigationEvent =
        event {
            setHypothesisRefuted(
                HypothesisRefuted
                    .newBuilder()
                    .setHypId(
                        hypId,
                    ).setRefutingStepId(refutingStepId)
                    .setReasoning(reasoning),
            )
        }

    fun hypothesisInconclusive(
        hypId: String,
        reason: String,
    ): InvestigationEvent =
        event { setHypothesisInconclusive(HypothesisInconclusive.newBuilder().setHypId(hypId).setReason(reason)) }

    fun budgetThreshold(
        usedUsd: Double,
        remainingUsd: Double,
        projectedNextBatchUsd: Double,
        severity: String,
    ): InvestigationEvent =
        event {
            setBudgetThreshold(
                BudgetThreshold
                    .newBuilder()
                    .setUsedUsd(usedUsd)
                    .setRemainingUsd(remainingUsd)
                    .setProjectedNextBatchUsd(projectedNextBatchUsd)
                    .setSeverity(severity),
            )
        }

    fun budgetExhausted(
        triggeredAction: String,
        remainingPlannedSteps: List<String>,
    ): InvestigationEvent =
        event {
            setBudgetExhausted(
                BudgetExhausted
                    .newBuilder()
                    .setTriggeredAction(
                        triggeredAction,
                    ).addAllRemainingPlannedSteps(remainingPlannedSteps),
            )
        }

    fun hypothesesPrioritized(ordered: List<PrioritizedHyp>): InvestigationEvent =
        event { setHypothesesPrioritized(HypothesesPrioritized.newBuilder().addAllOrdered(ordered)) }

    fun deepeningDecision(
        choseHypId: String,
        score: Double,
        rationale: String,
        alternates: List<String>,
        tieBreakUsed: Boolean,
    ): InvestigationEvent =
        event {
            setDeepeningDecision(
                DeepeningDecision
                    .newBuilder()
                    .setChoseHypId(choseHypId)
                    .setScore(score)
                    .setRationale(rationale)
                    .addAllAlternates(alternates)
                    .setTieBreakUsed(tieBreakUsed),
            )
        }

    fun planRevised(
        plan: PlanDag,
        revisionKind: RevisionKind,
        trigger: String,
    ): InvestigationEvent =
        event {
            setPlanRevised(
                PlanRevised
                    .newBuilder()
                    .setPlan(plan)
                    .setRevisionKind(revisionKind)
                    .setTrigger(trigger),
            )
        }

    fun looseEndDeclared(looseEnd: LooseEnd): InvestigationEvent =
        event { setLooseEndDeclared(LooseEndDeclared.newBuilder().setLooseEnd(looseEnd)) }

    fun looseEndGenerated(looseEnd: LooseEnd): InvestigationEvent =
        event { setLooseEndGenerated(LooseEndGenerated.newBuilder().setLooseEnd(looseEnd)) }

    fun suspicionRaised(
        stepId: String,
        kind: String,
        severity: String,
        message: String,
    ): InvestigationEvent =
        event {
            setSuspicionRaised(
                SuspicionRaised
                    .newBuilder()
                    .setStepId(stepId)
                    .setKind(kind)
                    .setSeverity(severity)
                    .setMessage(message),
            )
        }

    fun finding(finding: Finding): InvestigationEvent =
        event {
            setFinding(FindingRecorded.newBuilder().setFinding(finding))
        }

    fun schedulerDrained(reason: String): InvestigationEvent =
        event { setSchedulerDrained(SchedulerDrained.newBuilder().setReason(reason)) }

    fun synthesizerBlockStarted(
        index: Int,
        kind: String,
    ): InvestigationEvent =
        event { setSynthesizerBlockStarted(SynthesizerBlockStarted.newBuilder().setBlockIndex(index).setKind(kind)) }

    fun synthesizerBlockCompleted(
        index: Int,
        block: org.tatrman.kantheon.envelope.v1.Block,
    ): InvestigationEvent =
        event {
            setSynthesizerBlockCompleted(
                SynthesizerBlockCompleted.newBuilder().setBlockIndex(index).setBlock(block),
            )
        }

    fun synthesizerDone(totalBlocks: Int): InvestigationEvent =
        event { setSynthesizerDone(SynthesizerDone.newBuilder().setTotalBlocks(totalBlocks)) }

    fun conclusion(conclusion: Conclusion): InvestigationEvent =
        event { setConclusion(ConclusionReached.newBuilder().setConclusion(conclusion)) }

    fun investigationDone(
        status: Status,
        usage: ResourceUsage,
    ): InvestigationEvent =
        event { setInvestigationDone(InvestigationDone.newBuilder().setStatus(status).setResourceUsage(usage)) }

    private inline fun event(block: InvestigationEvent.Builder.() -> Unit): InvestigationEvent =
        InvestigationEvent.newBuilder().apply(block).build()
}
