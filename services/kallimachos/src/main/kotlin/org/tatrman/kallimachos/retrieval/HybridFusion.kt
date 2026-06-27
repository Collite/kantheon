package org.tatrman.kallimachos.retrieval

import org.tatrman.kallimachos.adapters.fulltext.FullTextHit
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.adapters.vector.VectorHit
import org.tatrman.kallimachos.corpus.PartRecord

data class FusionConfig(
    val graphWeight: Double = 1.0,
    val k: Int = 8,
    val minScore: Double = 0.0,
    // Pages outrank raw parts when the wiki is compiled (S3.2 T7 — the graph
    // truly leads); degrades to parts when the wiki is thin.
    val pageBoost: Double = 2.0,
)

/** A compiled wiki page surfaced as a retrieval candidate (S3.2 — graph leads with pages). */
data class PageCandidate(
    val pageId: Long,
    val title: String,
    val text: String,
    val score: Double,
)

/**
 * Graph-primary fusion (architecture §8 step 4) — the inversion from doc-store:
 * the wiki-graph LEADS, vector + keyword BOOST. A chunk reached via the walk is
 * weighted by `graphWeight` (so graph-reached chunks rank first); vector (cosine)
 * + keyword (normalised tsrank) add recall. Each chunk is labelled by how it
 * surfaced (`GRAPH` if the walk reached it, else `VECTOR`/`KEYWORD`).
 *
 * Grounding is the RECALL signal: a query grounds iff some chunk's vector/keyword
 * relevance clears `min-score`. Graph siblings (reached structurally, no recall
 * match) are context, not grounding — so a thin/irrelevant match returns
 * NO_GROUNDING (empty, no fabricated citations) rather than sibling noise. With
 * an empty graph, fusion degrades to recall-only and still returns cited chunks
 * (the "thin wiki" risk, architecture §8/§14).
 */
class HybridFusion(
    private val relational: RelationalPort,
    private val config: FusionConfig,
) {
    private data class Scored(
        val part: PartRecord,
        val fused: Double,
        val relevance: Double,
        val lead: RetrievalLead,
    )

    fun fuse(
        keywordHits: List<FullTextHit>,
        vectorHits: List<VectorHit>,
        graphReachedPartIds: Set<Long>,
        notebookId: String,
        pages: List<PageCandidate> = emptyList(),
        k: Int = config.k,
    ): ContextResult {
        // Two score views, deliberately separate:
        //  • RANKING — both planes max-normalized to [0,1] so the fused sum combines
        //    comparable scales (raw cosine + relative tsrank would let one plane
        //    dominate the ordering by accident of scale).
        //  • GROUNDING — the ABSOLUTE relevance (raw cosine similarity / raw tsrank).
        //    Grounding asks "is this actually relevant", so it must not use the
        //    relative norm, which inflates the single best hit to 1.0 and would let a
        //    thin keyword match falsely clear min-score.
        val kwMax = keywordHits.maxOfOrNull { it.score } ?: 0.0
        val vecMax = vectorHits.maxOfOrNull { it.score } ?: 0.0
        val kwNorm = keywordHits.associate { it.partId to if (kwMax > 0) it.score / kwMax else 0.0 }
        val vecNorm = vectorHits.associate { it.partId to if (vecMax > 0) it.score / vecMax else 0.0 }
        val kwAbs = keywordHits.associate { it.partId to it.score }
        val vecAbs = vectorHits.associate { it.partId to it.score }

        val candidateIds = (keywordHits.map { it.partId } + vectorHits.map { it.partId } + graphReachedPartIds).toSet()

        val scored =
            candidateIds.mapNotNull { partId ->
                val part = relational.getPart(partId) ?: return@mapNotNull null
                val kw = kwNorm[partId] ?: 0.0
                val vec = vecNorm[partId] ?: 0.0
                val onGraph = partId in graphReachedPartIds
                val fused = config.graphWeight * (if (onGraph) 1.0 else 0.0) + vec + kw
                val lead =
                    when {
                        onGraph -> RetrievalLead.GRAPH
                        vec >= kw -> RetrievalLead.VECTOR
                        else -> RetrievalLead.KEYWORD
                    }
                val relevance = maxOf(vecAbs[partId] ?: 0.0, kwAbs[partId] ?: 0.0)
                Scored(part, fused, relevance, lead)
            }

        // Grounding is the recall signal — graph-only siblings (relevance 0) never
        // ground; a relevant page (compiled from matched parts) also grounds.
        val grounded =
            scored.any { it.relevance > 0.0 && it.relevance >= config.minScore } ||
                pages.any { it.score > 0.0 && it.score >= config.minScore }
        if (!grounded) return ContextResult(emptyList(), grounded = false)

        // Pages lead — they carry the page boost so the compiled wiki outranks raw parts.
        val pageChunks =
            pages
                .sortedByDescending { it.score }
                .map { p ->
                    RetrievedChunk(
                        partId = 0,
                        sourceId = 0,
                        pageId = p.pageId,
                        text = p.text,
                        score = config.pageBoost + p.score,
                        lead = RetrievalLead.GRAPH,
                        citation = Citations.citationForPage(p.pageId, p.title, notebookId),
                    )
                }

        // Trim to k BEFORE the per-chunk source lookup — only the survivors need a
        // citation, so we don't fetch a source for every candidate we then drop.
        val partBudget = (k.coerceAtLeast(1) - pageChunks.size).coerceAtLeast(0)
        val partChunks =
            scored
                .sortedWith(compareByDescending<Scored> { it.fused }.thenBy { it.part.id })
                .take(partBudget)
                .mapNotNull { s ->
                    val source = relational.getSource(s.part.sourceId) ?: return@mapNotNull null
                    RetrievedChunk(
                        partId = s.part.id,
                        sourceId = s.part.sourceId,
                        pageId = null,
                        text = s.part.contentText,
                        score = s.fused,
                        lead = s.lead,
                        citation = Citations.citationFor(s.part, source, notebookId),
                    )
                }

        return ContextResult((pageChunks + partChunks).take(k.coerceAtLeast(1)), grounded = true)
    }
}
