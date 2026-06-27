package org.tatrman.kantheon.pythia.revise

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.plan.PlanDecodeException
import org.tatrman.kantheon.pythia.plan.PlanValidator
import org.tatrman.kantheon.pythia.plan.Prompts
import org.tatrman.kantheon.pythia.plan.PythiaModels
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.HypStatus
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.RevisionKind
import org.tatrman.kantheon.pythia.v1.RevisionPolicy
import java.util.UUID

/** Outcome of a revision attempt. */
sealed interface ReviseResult {
    data class Revised(
        val plan: PlanDag,
    ) : ReviseResult

    data class NeedsApproval(
        val plan: PlanDag,
    ) : ReviseResult

    data class Halt(
        val reason: String,
    ) : ReviseResult

    /** The depth-budget revision cap is reached — stop deepening. */
    data object CapReached : ReviseResult
}

/**
 * The plan reviser (Stage 3.2 T3/T4): a STRONG-tier call chooses
 * PRUNE / PIVOT / DECOMPOSE / HALT; the mutation is applied, the plan re-validated
 * (feedback-retry, max 3), `revision` bumped, and `plan_revised` emitted. Revisions
 * are capped per `depth_budget` (SHALLOW 0 / NORMAL 2 / DEEP 4 — design RCA brake).
 * `on_plan_revision = APPROVE` returns [ReviseResult.NeedsApproval] (the orchestrator
 * parks AWAITING_PLAN_REVISION_APPROVAL).
 */
class PlanReviser(
    private val executor: PromptExecutor,
    private val validator: PlanValidator,
    private val emitter: EventEmitter,
    private val prompts: Prompts = Prompts(),
    private val maxAttempts: Int = 3,
) {
    private val log = LoggerFactory.getLogger(PlanReviser::class.java)

    suspend fun revise(
        investigationId: UUID,
        plan: PlanDag,
        trigger: String,
        depthBudget: DepthBudget,
        locale: String,
        constraints: Constraints,
        onPlanRevision: RevisionPolicy,
    ): ReviseResult {
        if (plan.revision >= maxRevisions(depthBudget)) return ReviseResult.CapReached
        var feedback: String? = null
        repeat(maxAttempts) { attempt ->
            val decision =
                try {
                    ReviserCodec.decode(compose(plan, trigger, locale, feedback))
                } catch (e: PlanDecodeException) {
                    feedback = "Your previous output was invalid: ${e.message}. Return a valid reviser JSON object."
                    return@repeat
                }
            if (decision.kind ==
                RevisionKind.REVISION_HALT
            ) {
                return ReviseResult.Halt(decision.rationale.ifBlank { "reviser HALT" })
            }
            val mutated = applyMutation(plan, decision)
            val errors = validator.validate(mutated, constraints)
            if (errors.isNotEmpty()) {
                feedback = "The revised plan was invalid: ${errors.joinToString("; ")}. Fix it."
                log.info("revision attempt {} invalid: {}", attempt + 1, errors)
                return@repeat
            }
            emitter.emit(investigationId, Events.planRevised(mutated, decision.kind, trigger))
            return if (onPlanRevision == RevisionPolicy.REVISION_APPROVE) {
                ReviseResult.NeedsApproval(mutated)
            } else {
                ReviseResult.Revised(mutated)
            }
        }
        return ReviseResult.Halt("reviser failed to produce a valid revision after $maxAttempts attempts")
    }

    /** Apply the chosen mutation, bumping `revision`. */
    fun applyMutation(
        plan: PlanDag,
        decision: ReviserDecision,
    ): PlanDag {
        val builder = plan.toBuilder().setRevision(plan.revision + 1)
        when (decision.kind) {
            RevisionKind.REVISION_PRUNE -> prune(builder, decision.affectedHypIds.toSet())
            RevisionKind.REVISION_PIVOT -> {
                abandon(builder, decision.affectedHypIds.toSet())
                addHypothesesWithNodes(builder, decision.newHypotheses)
            }
            RevisionKind.REVISION_DECOMPOSE -> addHypothesesWithNodes(builder, decision.newHypotheses)
            else -> {}
        }
        return builder.build()
    }

    /** Add new hypotheses plus a QueryNode that tests each (so the next batch can probe them). */
    private fun addHypothesesWithNodes(
        builder: PlanDag.Builder,
        newHypotheses: List<org.tatrman.kantheon.pythia.v1.Hypothesis>,
    ) {
        newHypotheses.forEach { hyp ->
            builder.addHypotheses(hyp)
            builder.addNodes(
                org.tatrman.kantheon.pythia.v1.PlanNode
                    .newBuilder()
                    .setNodeId("N-${hyp.id}")
                    .addTestsHypIds(hyp.id)
                    .setQuery(
                        org.tatrman.kantheon.pythia.v1.QueryNode
                            .newBuilder()
                            .setQueryRef(
                                "q.${hyp.id}",
                            ).setParamsJson("{}"),
                    ),
            )
        }
    }

    /** Remove the pruned hypotheses and any node that ONLY tested them. */
    private fun prune(
        builder: PlanDag.Builder,
        pruned: Set<String>,
    ) {
        val keptHyps = builder.hypothesesList.filter { it.id !in pruned }
        val keptNodes =
            builder.nodesList.filter { node ->
                node.testsHypIdsList.isEmpty() || node.testsHypIdsList.any { it !in pruned }
            }
        val keptNodeIds = keptNodes.map { it.nodeId }.toSet()
        val keptEdges = builder.edgesList.filter { it.fromNodeId in keptNodeIds && it.toNodeId in keptNodeIds }
        builder.clearHypotheses().addAllHypotheses(keptHyps)
        builder.clearNodes().addAllNodes(keptNodes)
        builder.clearEdges().addAllEdges(keptEdges)
    }

    /** Mark the pivoted-away hypotheses ABANDONED. */
    private fun abandon(
        builder: PlanDag.Builder,
        abandoned: Set<String>,
    ) {
        val updated =
            builder.hypothesesList.map {
                if (it.id in abandoned) it.toBuilder().setStatus(HypStatus.HYP_ABANDONED).build() else it
            }
        builder.clearHypotheses().addAllHypotheses(updated)
    }

    private suspend fun compose(
        plan: PlanDag,
        trigger: String,
        locale: String,
        feedback: String?,
    ): String {
        val template = prompts.load(locale, "reviser")
        val user =
            Prompts.substitute(
                template,
                mapOf(
                    "trigger" to trigger,
                    "hypotheses" to
                        plan.hypothesesList.joinToString("; ") { "${it.id}:${it.statement}(${it.status.name})" },
                    "feedback" to (feedback ?: ""),
                ),
            )
        val p =
            prompt("pythia-revise") {
                system(
                    "You revise the investigation plan. Choose PRUNE, PIVOT, DECOMPOSE, or HALT. Return only the JSON object.",
                )
                user(user)
            }
        return executor
            .execute(p, PythiaModels.Strong, emptyList())
            .filterIsInstance<Message.Assistant>()
            .joinToString("\n") { it.content }
    }

    private fun maxRevisions(depth: DepthBudget): Int =
        when (depth) {
            DepthBudget.DEPTH_SHALLOW -> 0
            DepthBudget.DEPTH_DEEP -> 4
            else -> 2 // NORMAL / unspecified
        }
}
