package org.tatrman.kantheon.pythia.executor

import org.tatrman.kantheon.pythia.clients.QueryClient
import org.tatrman.kantheon.pythia.dataplane.InListMaterialiser
import org.tatrman.kantheon.pythia.v1.PlanNode

/**
 * Executes a `QueryNode` against query-mcp (Stage 2.3 T2). Resolves `HandleRef`
 * params from the handle table, enforces the IN-list ≤ 500 rule, compiles a composed
 * stack before running it, stores the result as a `PgResultSnapshot` (small) or
 * `LiveQueryRef`, and forwards `pipeline_warnings` (Rule-6). A bearer rejection
 * surfaces as [TokenExpiredException] (→ park AWAITING_USER_INPUT).
 *
 * Stage 4.1 T7 activates the **IN-list>500 materialise path**: when [inListMaterialiser]
 * is wired, an over-cap id-list is staged into a worker DF (Charon `Stage`) and the
 * param rebound to a `$dfRef` (a semi-join) instead of inlining — no PERMANENT flag.
 * Without a materialiser (Phase 2 deployments) the over-cap list is still a PERMANENT
 * failure flagged for materialise.
 */
class QueryNodeExecutor(
    private val queryClient: QueryClient,
    private val sourceLanguage: String = "named",
    private val targetDialect: String = "postgres",
    private val inlineRowCap: Int = 1_000,
    private val rowLimit: Int? = 5_000,
    private val inListMaterialiser: InListMaterialiser? = null,
) : NodeExecutor {
    override fun providerOf(node: PlanNode): String = "query"

    override suspend fun execute(
        node: PlanNode,
        ctx: NodeContext,
    ): NodeResult {
        val query = node.query
        var paramsTemplate = query.paramsJson.ifBlank { "{}" }

        // IN-list>500: a HandleRef projecting an over-cap column must be materialised
        // (staged to a worker DF) rather than inlined. With a materialiser wired we
        // stage + rebind to a $dfRef; otherwise it stays a PERMANENT materialise flag.
        // NB: the cap is enforced only on `$handleRef`-projected columns (the only lists that
        // *can* be staged) — a literal over-cap array hand-inlined into paramsJson is not caught
        // here; the planner is constrained to project large id-sets through handles, not literals.
        val overCap = ctx.handles.handleRefs(paramsTemplate).filter { it.size > IN_LIST_CAP }
        if (overCap.isNotEmpty()) {
            if (inListMaterialiser == null) {
                throw NodeExecutionException(
                    FailureKind.PERMANENT,
                    "in-list of ${overCap.maxOf { it.size }} exceeds the $IN_LIST_CAP cap — requires materialise",
                )
            }
            for (ref in overCap) {
                val stagedId = inListMaterialiser.materialise(ref.handle, ctx.handles, ctx.sessionId)
                paramsTemplate = ctx.handles.replaceWithDfRef(paramsTemplate, ref.handle, ref.projection, stagedId)
            }
        }

        val resolvedParams = ctx.handles.resolveBindings(paramsTemplate)
        val warnings = mutableListOf<String>()
        // compile-before-run for composed stacks (TransDSL ops on the node's stack).
        if (node.query.stackCount > 0) {
            val compiled = queryClient.compile(query.queryRef, sourceLanguage, targetDialect, ctx.bearer)
            warnings += compiled.warnings
        }
        val result = queryClient.query(query.queryRef, sourceLanguage, resolvedParams, rowLimit, ctx.bearer)
        warnings += result.warnings

        val handleId = "h-${node.nodeId}"
        val handle =
            if (result.rowCount <= inlineRowCap && !result.truncated) {
                ctx.handles.putSnapshot(handleId, result.rows).handle
            } else {
                ctx.handles.putLiveQuery(handleId, query.queryRef, resolvedParams)
            }
        return NodeResult(outputHandle = handle, rowCount = result.rowCount, costUsd = 0.0, warnings = warnings)
    }

    private companion object {
        const val IN_LIST_CAP = 500
    }
}
