package org.tatrman.kantheon.pythia.revise

import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.Hypothesis
import org.tatrman.kantheon.pythia.v1.LooseEnd
import org.tatrman.kantheon.pythia.v1.LooseEndReason
import org.tatrman.kantheon.pythia.v1.LooseEndSource
import org.tatrman.kantheon.pythia.v1.PlanDag

/**
 * Derives loose ends (Stage 3.2 T5, design §3): a PLANNING_TIME sweep (hypotheses
 * the plan declared but no node tests) and an EXECUTION_TIME orphan sweep
 * (hypotheses left INCONCLUSIVE / ABANDONED). Each carries a `suggested_followup`.
 */
object LooseEndDeriver {
    fun planningTime(plan: PlanDag): List<LooseEnd> {
        val testedHypIds = plan.nodesList.flatMap { it.testsHypIdsList }.toSet()
        return plan.hypothesesList
            .filter { it.id !in testedHypIds }
            .map {
                LooseEnd
                    .newBuilder()
                    .setHypothesisId(it.id)
                    .setSource(LooseEndSource.LOOSE_END_PLANNING_TIME)
                    .setReason(LooseEndReason.LOOSE_END_DEPRIORITIZED_BY_DEPTH)
                    .setWhy("hypothesis '${it.statement}' was declared but not tested within the depth budget")
                    .setSuggestedFollowup("re-run at a deeper depth budget to test: ${it.statement}")
                    .build()
            }
    }

    fun executionTime(hypotheses: List<Hypothesis>): List<LooseEnd> =
        hypotheses
            .filter { it.status == HypStatus.HYP_INCONCLUSIVE || it.status == HypStatus.HYP_ABANDONED }
            .map {
                LooseEnd
                    .newBuilder()
                    .setHypothesisId(it.id)
                    .setSource(LooseEndSource.LOOSE_END_EXECUTION_TIME)
                    .setReason(LooseEndReason.LOOSE_END_INCONCLUSIVE_ABANDONED)
                    .setWhy("hypothesis '${it.statement}' ended ${it.status.name} without a conclusive verdict")
                    .setSuggestedFollowup("investigate '${it.statement}' with a targeted query/model step")
                    .build()
            }
}
