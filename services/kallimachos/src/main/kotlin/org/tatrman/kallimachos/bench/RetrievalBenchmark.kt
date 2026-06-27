package org.tatrman.kallimachos.bench

import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.retrieval.GraphWalk
import org.tatrman.kallimachos.retrieval.KeywordRecall
import org.tatrman.kallimachos.retrieval.VectorRecall

data class PlaneStat(
    val plane: String,
    val candidates: Int,
    val latencyMs: Long,
)

/**
 * A per-plane retrieval benchmark report (architecture §13 metrics:
 * `kallimachos_retrieval_candidates{plane}`, `kallimachos_graph_walk_depth`). It
 * measures candidate counts + latency per plane on a reference mart; the numbers
 * feed the P4 `cost_hints` on the `library.*` manifests.
 */
data class BenchmarkReport(
    val query: String,
    val notebookId: String,
    val planes: List<PlaneStat>,
    val graphWalkDepth: Int,
) {
    fun candidatesFor(plane: String): Int = planes.firstOrNull { it.plane == plane }?.candidates ?: 0
}

/**
 * Harness for the S2.3 benchmark (`bench/`). NOT a JMH micro-benchmark — a
 * wall-clock count of candidates per plane on a mart, run on the integration
 * reference corpus to produce the `cost_hints` numbers. Uses an injected clock
 * so the unit spec is deterministic (no `Date.now()` in the hot path).
 */
class RetrievalBenchmark(
    private val keywordRecall: KeywordRecall,
    private val vectorRecall: VectorRecall,
    private val graphWalk: GraphWalk,
    private val relational: RelationalPort,
    private val notebooks: NotebookPort,
    private val clockMs: () -> Long,
) {
    suspend fun run(
        notebookId: String,
        query: String,
        k: Int = 8,
        graphHops: Int = 2,
    ): BenchmarkReport {
        val allowed = notebooks.memberSourceIds(notebookId)
        val planes = mutableListOf<PlaneStat>()

        val (keywordHits, kwMs) = timed { keywordRecall.recall(query, emptyList(), k, allowed) }
        planes += PlaneStat("keyword", keywordHits.size, kwMs)

        val (vectorHits, vecMs) = timed { vectorRecall.recall(query, k, allowed) }
        planes += PlaneStat("vector", vectorHits.size, vecMs)

        val seeds = (keywordHits.map { it.partId } + vectorHits.map { it.partId }).toSet()
        val seedNodes =
            seeds.map { GraphNodeRef("part", it) } +
                seeds.mapNotNull { relational.getPart(it)?.sourceId }.distinct().map { GraphNodeRef("source", it) }
        val (walked, graphMs) =
            timed {
                graphWalk
                    .walk(
                        seedNodes,
                        setOf(GraphEdgeKind.CONTAINS),
                        graphHops,
                        allowed,
                    ).filter { it.kind == "part" }
            }
        planes += PlaneStat("graph", walked.size, graphMs)

        return BenchmarkReport(query, notebookId, planes, graphWalkDepth = graphHops)
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val t0 = clockMs()
        val result = block()
        return result to (clockMs() - t0)
    }
}
