package org.tatrman.kantheon.pythia.executor

import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Routes each plan node to its kind-specific [NodeExecutor] (QueryNode → theseus,
 * ReasoningNode → LLM, RenderNode → envelope blocks; Phase 4 adds DataFrameNode →
 * Polars worker and ModelNode → Metis). A node kind whose executor is not wired
 * fails closed (PERMANENT) so a stray node never passes silently — e.g. a
 * DataFrame plan in an SQL-only deployment.
 */
class CompositeNodeExecutor(
    private val query: NodeExecutor,
    private val render: NodeExecutor,
    private val reasoning: NodeExecutor,
    private val dataframe: NodeExecutor? = null,
    private val model: NodeExecutor? = null,
) : NodeExecutor {
    override fun providerOf(node: PlanNode): String = pick(node).providerOf(node)

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult = pick(node).execute(node, ctx)

    private fun pick(node: PlanNode): NodeExecutor =
        when (node.kindCase) {
            PlanNode.KindCase.QUERY -> query
            PlanNode.KindCase.RENDER -> render
            PlanNode.KindCase.REASONING -> reasoning
            PlanNode.KindCase.DATAFRAME -> dataframe ?: unsupported("DataFrameNode (Polars worker not wired)")
            PlanNode.KindCase.MODEL -> model ?: unsupported("ModelNode (Metis not wired)")
            else -> unsupported("kind ${node.kindCase}")
        }

    private fun unsupported(what: String): NodeExecutor =
        object : NodeExecutor {
            override suspend fun execute(
                node: PlanNode,
                ctx: NodeContext,
            ): NodeResult =
                throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "node ${node.nodeId}: $what is not supported in this deployment",
                )
        }
}
