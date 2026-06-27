package org.tatrman.kantheon.pythia.synth

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.pythia.executor.NodeContext
import org.tatrman.kantheon.pythia.executor.NodeExecutor
import org.tatrman.kantheon.pythia.executor.NodeResult
import org.tatrman.kantheon.pythia.plan.PythiaModels
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.ReasoningNode

/**
 * Executes a `ReasoningNode` — an LLM reasoning step over upstream handles. The
 * tier follows `tier_hint` (STRONG | CHEAP). The text output is stored as a
 * single-row snapshot `{"reasoning": "..."}` so downstream nodes can bind to it.
 */
class ReasoningNodeExecutor(
    private val executor: PromptExecutor,
) : NodeExecutor {
    override fun providerOf(node: PlanNode): String = "llm"

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val reasoning = node.reasoning
        val inputs = reasoning.inputHandleIdsList.mapNotNull { ctx.handles.rows(it)?.toString() }.joinToString("\n")
        val model: LLModel =
            if (reasoning.tierHint ==
                ReasoningNode.TierHint.TIER_STRONG
            ) {
                PythiaModels.Strong
            } else {
                PythiaModels.Cheap
            }
        val p =
            prompt("pythia-reasoning") {
                system("You are Pythia's reasoning step. Answer concisely.")
                user("${reasoning.promptRef}\n\nInputs:\n$inputs")
            }
        val text =
            executor
                .execute(p, model, emptyList())
                .filterIsInstance<Message.Assistant>()
                .joinToString(" ") { it.content }
        val rows = JsonArray(listOf(JsonObject(mapOf("reasoning" to JsonPrimitive(text)))))
        val handle = ctx.handles.putSnapshot("h-${node.nodeId}", rows).handle
        val cost = if (reasoning.tierHint == ReasoningNode.TierHint.TIER_STRONG) 0.05 else 0.002
        return NodeResult(outputHandle = handle, rowCount = 1, costUsd = cost)
    }
}
