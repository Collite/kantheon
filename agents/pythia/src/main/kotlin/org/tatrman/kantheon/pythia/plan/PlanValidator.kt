package org.tatrman.kantheon.pythia.plan

import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode

/** A structured validation error fed back into the composer retry loop. */
data class ValidationError(
    val code: String,
    val detail: String,
) {
    override fun toString(): String = "$code: $detail"
}

/** Checks a capability/query id exists in the registry (capabilities-mcp). */
fun interface CapabilityChecker {
    suspend fun exists(capabilityId: String): Boolean
}

/**
 * Typed plan preconditions (architecture §5): every `DataDep` resolves to a node
 * and carries a binding; each `QueryNode.queryRef` exists in the capability
 * registry; the plan is acyclic; and node count respects `max_step_count` +
 * the `depth_budget` cap. Returns structured errors consumed by the retry loop.
 */
class PlanValidator(
    private val capabilities: CapabilityChecker,
) {
    suspend fun validate(
        plan: PlanDag,
        constraints: Constraints,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val nodeIds = plan.nodesList.map { it.nodeId }.toSet()
        if (plan.nodesList.isEmpty()) errors += ValidationError("empty_plan", "the plan has no nodes")

        // DataDep integrity: endpoints resolve + binding present.
        for (edge in plan.edgesList) {
            if (edge.fromNodeId !in nodeIds) {
                errors += ValidationError("dangling_dep", "edge from unknown node '${edge.fromNodeId}'")
            }
            if (edge.toNodeId !in nodeIds) {
                errors += ValidationError("dangling_dep", "edge to unknown node '${edge.toNodeId}'")
            }
            if (edge.binding.isBlank()) {
                errors += ValidationError("invalid_binding", "edge ${edge.fromNodeId}→${edge.toNodeId} has no binding")
            }
        }

        // Capability existence for QueryNodes (theseus) + ModelNodes (Metis, Phase 4).
        for (node in plan.nodesList) {
            when (node.kindCase) {
                PlanNode.KindCase.QUERY ->
                    if (!capabilities.exists(node.query.queryRef)) {
                        errors +=
                            ValidationError(
                                "unknown_capability",
                                "queryRef '${node.query.queryRef}' not in the registry",
                            )
                    }
                PlanNode.KindCase.MODEL ->
                    if (!capabilities.exists(node.model.capabilityId)) {
                        errors +=
                            ValidationError(
                                "unknown_capability",
                                "ModelNode capability '${node.model.capabilityId}' not in the registry",
                            )
                    }
                PlanNode.KindCase.DATAFRAME ->
                    if (node.dataframe.sourceHandleId.isBlank()) {
                        errors +=
                            ValidationError(
                                "invalid_dataframe",
                                "DataFrameNode ${node.nodeId} has no source_handle_id",
                            )
                    }
                else -> {}
            }
        }

        // Hypotheses referenced by nodes must exist.
        val hypIds = plan.hypothesesList.map { it.id }.toSet()
        for (node in plan.nodesList) {
            node.testsHypIdsList.filter { it !in hypIds }.forEach {
                errors += ValidationError("unknown_hypothesis", "node ${node.nodeId} tests unknown hypothesis '$it'")
            }
        }

        // Depth caps.
        if (constraints.maxStepCount > 0 && plan.nodesCount > constraints.maxStepCount) {
            errors +=
                ValidationError("over_step_cap", "plan has ${plan.nodesCount} nodes > max ${constraints.maxStepCount}")
        }
        val depthCap = depthCap(constraints.depthBudget)
        if (plan.nodesCount > depthCap) {
            errors +=
                ValidationError(
                    "over_depth_budget",
                    "plan has ${plan.nodesCount} nodes > ${constraints.depthBudget} cap $depthCap",
                )
        }

        // Acyclicity (the executor assumes a DAG; assert defensively).
        if (hasCycle(plan)) errors += ValidationError("cyclic", "the plan graph contains a cycle")

        return errors
    }

    private fun depthCap(budget: DepthBudget): Int =
        when (budget) {
            DepthBudget.DEPTH_SHALLOW -> 3
            DepthBudget.DEPTH_DEEP -> 50
            else -> 15 // NORMAL / unspecified
        }

    private fun hasCycle(plan: PlanDag): Boolean {
        val adj = plan.edgesList.groupBy({ it.fromNodeId }, { it.toNodeId })
        val state = mutableMapOf<String, Int>() // 0=unseen,1=in-stack,2=done

        fun dfs(n: String): Boolean {
            when (state[n]) {
                1 -> return true
                2 -> return false
            }
            state[n] = 1
            if (adj[n]?.any { dfs(it) } == true) return true
            state[n] = 2
            return false
        }
        return plan.nodesList.any { dfs(it.nodeId) }
    }
}
