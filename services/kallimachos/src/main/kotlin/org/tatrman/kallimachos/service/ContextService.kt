package org.tatrman.kallimachos.service

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.GraphPort
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.adapters.vector.VectorHit
import org.tatrman.kallimachos.retrieval.ContextResult
import org.tatrman.kallimachos.retrieval.GraphWalk
import org.tatrman.kallimachos.retrieval.HybridFusion
import org.tatrman.kallimachos.retrieval.KeywordRecall
import org.tatrman.kallimachos.retrieval.PageCandidate
import org.tatrman.kallimachos.retrieval.VectorRecall

/**
 * `getContext` — the graph-primary, citation-bearing RAG primitive (architecture
 * §8). Orchestrates the four steps: SEED (keyword + vector recall) → WALK (graph
 * from the seed nodes) → BOOST (the recall scores) → FUSE ([HybridFusion]). Mart
 * scope is mandatory; `"*"` is admin-only whole-corpus.
 *
 * Vector recall is best-effort: if the LLM gateway is unreachable (no embeddings yet),
 * it degrades to graph + keyword rather than failing the query (the "thin wiki"
 * resilience, architecture §14).
 */
class ContextService(
    private val relational: RelationalPort,
    private val notebooks: NotebookPort,
    private val graph: GraphPort,
    private val graphWalk: GraphWalk,
    private val vectorRecall: VectorRecall,
    private val keywordRecall: KeywordRecall,
    private val fusion: HybridFusion,
    private val defaultK: Int = 8,
    private val defaultGraphHops: Int = 2,
) {
    private val log = LoggerFactory.getLogger(ContextService::class.java)

    suspend fun getContext(
        notebookId: String,
        query: String,
        k: Int = defaultK,
        graphHops: Int = defaultGraphHops,
        vectorBoost: Boolean = true,
    ): ContextResult {
        val allowed = scope(notebookId) ?: return adminScope(query, k, graphHops, vectorBoost)
        if (allowed.isEmpty()) return ContextResult(emptyList(), grounded = false)

        val expand = maxOf(k * EXPANSION, k)
        // The two recall planes are independent — run them concurrently (vector
        // recall includes an LLM-gateway embed round-trip; keyword is a separate
        // tsquery). Latency collapses to the slower plane rather than their sum.
        val (keywordHits, vectorHits) =
            coroutineScope {
                val kw = async { keywordRecall.recall(query, emptyList(), expand, allowed) }
                val vec = async { if (vectorBoost) recallVectors(query, expand, allowed) else emptyList() }
                kw.await() to vec.await()
            }

        val candidateParts = keywordHits.map { it.partId } + vectorHits.map { it.partId }
        val graphReached = walk(candidateParts, graphHops, allowed)
        val pages = gatherPages(candidateParts.toSet() + graphReached)
        return fusion.fuse(keywordHits, vectorHits, graphReached, notebookId, pages, k)
    }

    /**
     * The compiled wiki pages the candidate parts feed (S3.2 T7 — pages lead). For
     * each candidate part, the reverse `DERIVED_FROM` edges name the pages it
     * compiled into; ENTITY/CONCEPT pages outrank the SUMMARY. Empty until the P3
     * compile thickens the wiki — retrieval degrades to parts.
     */
    private fun gatherPages(partIds: Set<Long>): List<PageCandidate> {
        val pageIds =
            partIds
                .flatMap { graph.incoming(GraphNodeRef("part", it), setOf(GraphEdgeKind.DERIVED_FROM)) }
                .map { it.from }
                .filter { it.kind == "page" }
                .map { it.id }
                .toSet()
        return pageIds.mapNotNull { id ->
            relational.getPage(id)?.let { page ->
                val score = if (page.kind == "ENTITY" || page.kind == "CONCEPT") 1.0 else 0.7
                PageCandidate(id, page.title, page.contentMd, score)
            }
        }
    }

    suspend fun findSimilar(
        notebookId: String,
        query: String,
        k: Int = defaultK,
    ): ContextResult {
        val allowed = scope(notebookId)
        if (allowed != null && allowed.isEmpty()) return ContextResult(emptyList(), grounded = false)
        val vectorHits = recallVectors(query, k, allowed)
        // Vector-led + a graph-proximity boost from the vector seeds' siblings.
        val graphReached = walk(vectorHits.map { it.partId }, defaultGraphHops, allowed)
        return fusion.fuse(emptyList(), vectorHits, graphReached, notebookId, k = k)
    }

    private suspend fun adminScope(
        query: String,
        k: Int,
        graphHops: Int,
        vectorBoost: Boolean,
    ): ContextResult {
        val expand = maxOf(k * EXPANSION, k)
        val keywordHits = keywordRecall.recall(query, emptyList(), expand, null)
        val vectorHits = if (vectorBoost) recallVectors(query, expand, null) else emptyList()
        val graphReached = walk(keywordHits.map { it.partId } + vectorHits.map { it.partId }, graphHops, null)
        return fusion.fuse(keywordHits, vectorHits, graphReached, "*", k = k)
    }

    /**
     * The graph EXPANSION beyond the recall seeds: parts the walk reaches from the
     * seed nodes that were NOT themselves recall hits. Recall hits keep their
     * VECTOR/KEYWORD lead; the expansion siblings are the GRAPH-led context. (At
     * P3, page seeds make the graph genuinely lead; at P2 it expands source parts.)
     */
    private fun walk(
        seedPartIds: List<Long>,
        hops: Int,
        allowed: Set<Long>?,
    ): Set<Long> {
        val seeds = seedPartIds.toSet()
        val seedNodes =
            seeds.map { GraphNodeRef("part", it) } +
                seeds.mapNotNull { relational.getPart(it)?.sourceId }.distinct().map { GraphNodeRef("source", it) }
        return graphWalk
            .walk(seedNodes, setOf(GraphEdgeKind.CONTAINS), hops, allowed)
            .filter { it.kind == "part" }
            .map { it.id }
            .toSet()
            .minus(seeds) // expansion only — seeds keep their recall lead
    }

    private suspend fun recallVectors(
        query: String,
        k: Int,
        allowed: Set<Long>?,
    ): List<VectorHit> =
        try {
            vectorRecall.recall(query, k, allowed)
        } catch (e: Exception) {
            log.debug("vector recall unavailable, degrading to graph + keyword: {}", e.message)
            emptyList()
        }

    private fun scope(notebookId: String): Set<Long>? =
        if (notebookId == ADMIN_SCOPE) null else notebooks.memberSourceIds(notebookId)

    companion object {
        const val ADMIN_SCOPE = "*"
        const val EXPANSION = 3 // recall over-fetch before fusion trims to k
    }
}
