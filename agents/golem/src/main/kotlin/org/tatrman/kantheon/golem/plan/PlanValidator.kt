package org.tatrman.kantheon.golem.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.golem.v1.MiniPlan
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.golem.v1.QueryNode

/** One reason a [MiniPlan] failed validation; [nodeId] null = plan-level. */
data class PlanViolation(
    val nodeId: String?,
    val message: String,
)

data class PlanValidationResult(
    val violations: List<PlanViolation>,
) {
    val isValid: Boolean get() = violations.isEmpty()
}

/**
 * Deterministic structural validation of a composer-produced [MiniPlan] before the
 * gate (architecture §4 `pick_plan` pre-check). Checks node count/ids, linear deps
 * (v1: a node may only reference earlier nodes), PATTERN `pattern_id` existence +
 * required params against the [ModelSnapshot], and the FREE_SQL ⇒ `compile_first`
 * rule. Pure — no LLM, no I/O.
 */
class PlanValidator(
    private val maxStepCount: Int = 4,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(
        plan: MiniPlan,
        model: ModelSnapshot?,
    ): PlanValidationResult {
        val v = mutableListOf<PlanViolation>()
        val nodes = plan.nodesList

        if (nodes.isEmpty()) v += PlanViolation(null, "plan has no nodes")
        if (nodes.size > maxStepCount) {
            v += PlanViolation(null, "plan has ${nodes.size} nodes, exceeding max_step_count $maxStepCount")
        }

        val seenIds = mutableSetOf<String>()
        nodes.forEach { node ->
            val id = node.nodeId
            when {
                id.isBlank() -> v += PlanViolation(null, "a node has a blank node_id")
                id in seenIds -> v += PlanViolation(id, "duplicate node_id '$id'")
            }
            // Linear-deps rule: every referenced input must be an EARLIER node.
            inputsOf(node).forEach { ref ->
                if (ref !in
                    seenIds
                ) {
                    v += PlanViolation(id, "node '$id' references '$ref', which is not an earlier node")
                }
            }
            if (node.kindCase == MiniPlanNode.KindCase.QUERY) validateQuery(id, node.query, plan.source, model, v)
            seenIds += id
        }
        return PlanValidationResult(v)
    }

    private fun validateQuery(
        nodeId: String,
        q: QueryNode,
        source: PlanSource,
        model: ModelSnapshot?,
        v: MutableList<PlanViolation>,
    ) {
        if (source == PlanSource.FREE_SQL && !q.compileFirst) {
            v += PlanViolation(nodeId, "FREE_SQL query must set compile_first")
        }
        if (!q.hasPatternId()) return

        if (model == null) return // model not loaded — pattern checks deferred to a loaded turn
        val pattern = model.patternQuery(q.patternId)
        if (pattern == null) {
            v += PlanViolation(nodeId, "pattern_id '${q.patternId}' is not in the model")
            return
        }
        // Note (Δ2): a missing *required* param is NOT a validation failure — the parametrization
        // rail asks the user for it via a `param_fill` clarification at execute. The validator only
        // rejects *unknown* params (a hallucinated key the pattern doesn't declare).
        val declared = pattern.parametersList.map { it.name }.toSet()
        paramKeys(q.paramsJson)
            .filter { it !in declared }
            .forEach { v += PlanViolation(nodeId, "pattern '${q.patternId}' has no param '$it'") }
    }

    private fun inputsOf(node: MiniPlanNode): List<String> =
        when (node.kindCase) {
            MiniPlanNode.KindCase.REASONING -> node.reasoning.inputNodeIdsList
            MiniPlanNode.KindCase.RENDER -> node.render.inputNodeIdsList
            else -> emptyList()
        }

    private fun paramKeys(paramsJson: String): Set<String> =
        runCatching {
            if (paramsJson.isBlank()) emptySet() else json.parseToJsonElement(paramsJson).jsonObject.keys
        }.getOrDefault(emptySet())
}
