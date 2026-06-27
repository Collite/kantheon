package org.tatrman.kantheon.pythia.executor

import org.tatrman.kantheon.pythia.v1.PlanDag
import org.tatrman.kantheon.pythia.v1.PlanNode

/** Pure DAG topology helpers (architecture §5 "DAG executor"). */
object Topology {
    /**
     * The frontier = nodes neither completed nor [blocked] whose every incoming
     * `DataDep` source is completed. (A node with no incoming edges is ready
     * immediately.) A [blocked] node — one whose upstream permanently failed — is
     * never returned, and because its id is absent from [completed] its own
     * dependents are never satisfied either, so a failed branch prunes cleanly
     * instead of letting dependents run against a missing handle.
     */
    fun frontier(
        plan: PlanDag,
        completed: Set<String>,
        blocked: Set<String> = emptySet(),
    ): List<PlanNode> =
        plan.nodesList.filter { node ->
            node.nodeId !in completed &&
                node.nodeId !in blocked &&
                plan.edgesList.filter { it.toNodeId == node.nodeId }.all { it.fromNodeId in completed }
        }

    /** DFS three-colour cycle detection; the executor assumes a DAG and asserts defensively. */
    fun hasCycle(plan: PlanDag): Boolean {
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
