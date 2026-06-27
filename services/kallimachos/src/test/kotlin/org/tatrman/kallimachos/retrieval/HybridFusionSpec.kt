package org.tatrman.kallimachos.retrieval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.fulltext.FullTextHit
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource
import org.tatrman.kallimachos.adapters.vector.VectorHit

/**
 * P2 Stage 2.3 T1 — graph-primary fusion. The graph LEADS (graph-reached chunks
 * rank first, `lead = GRAPH`); vector/keyword BOOST; cross-plane normalisation;
 * `min-score` NO_GROUNDING; empty-graph recall-only degradation.
 */
class HybridFusionSpec :
    StringSpec({
        // One source with four parts (ids 2..5 under source 1).
        fun corpus(): InMemoryRelationalAdapter {
            val r = InMemoryRelationalAdapter()
            val s = r.insertSource(NewSource(title = "Doc"))
            r.insertParts(s.id, (0..3).map { NewPart(it, "paragraph", "part $it text") })
            return r
        }

        fun fusion(
            r: InMemoryRelationalAdapter,
            minScore: Double = 0.0,
        ) = HybridFusion(r, FusionConfig(graphWeight = 1.0, k = 8, minScore = minScore))

        "the graph leads — a graph-reached chunk ranks above a pure-keyword chunk" {
            val r = corpus()
            // part 2: keyword + graph ; part 3: keyword only (not graph-reached)
            val keyword = listOf(FullTextHit(2, 1, 1.0, "a"), FullTextHit(3, 1, 1.0, "b"))
            val result = fusion(r).fuse(keyword, emptyList(), graphReachedPartIds = setOf(2L), notebookId = "nb")

            result.grounded shouldBe true
            result.chunks.first().partId shouldBe 2L // graph weight lifts it above the equal-keyword part 3
            result.chunks.first().lead shouldBe RetrievalLead.GRAPH
            result.chunks.first { it.partId == 3L }.lead shouldBe RetrievalLead.KEYWORD
        }

        "lead labels reflect how a chunk surfaced" {
            val r = corpus()
            val keyword = listOf(FullTextHit(2, 1, 1.0, "a"))
            val vector = listOf(VectorHit(3, 1, 0.9))
            val result = fusion(r).fuse(keyword, vector, graphReachedPartIds = setOf(4L), notebookId = "nb")
            // part 4 is graph-only — still included as context (GRAPH), but recall grounds the query.
            result.chunks.first { it.partId == 2L }.lead shouldBe RetrievalLead.KEYWORD
            result.chunks.first { it.partId == 3L }.lead shouldBe RetrievalLead.VECTOR
            result.chunks.first { it.partId == 4L }.lead shouldBe RetrievalLead.GRAPH
        }

        "with an empty graph, fusion degrades to recall-only and still cites" {
            val r = corpus()
            val result =
                fusion(r).fuse(
                    listOf(FullTextHit(2, 1, 1.0, "a")),
                    listOf(VectorHit(3, 1, 0.8)),
                    graphReachedPartIds = emptySet(),
                    notebookId = "nb",
                )
            result.grounded shouldBe true
            result.chunks.map { it.partId }.toSet() shouldBe setOf(2L, 3L)
            result.chunks.all { it.citation.sourceRef.startsWith("kallimachos://nb/") } shouldBe true
        }

        "below min-score → NO_GROUNDING (empty, no fabricated citations)" {
            val r = corpus()
            // Vector cosine is absolute (not max-normalised), so min-score bites:
            // a 0.2 recall under a 0.9 threshold does not ground, and graph
            // siblings (relevance 0) never ground on their own.
            val below = fusion(r, minScore = 0.9).fuse(emptyList(), listOf(VectorHit(3, 1, 0.2)), setOf(4L), "nb")
            below.grounded shouldBe false
            below.chunks.shouldBeEmpty()

            val graphOnly = fusion(r, minScore = 0.1).fuse(emptyList(), emptyList(), setOf(4L), "nb")
            graphOnly.grounded shouldBe false

            val above = fusion(r, minScore = 0.5).fuse(emptyList(), listOf(VectorHit(3, 1, 0.8)), emptySet(), "nb")
            above.grounded shouldBe true
        }

        "when the wiki is compiled, pages lead — they outrank raw parts (T7)" {
            val r = corpus()
            val result =
                fusion(r).fuse(
                    keywordHits = listOf(FullTextHit(2, 1, 1.0, "a")),
                    vectorHits = emptyList(),
                    graphReachedPartIds = emptySet(),
                    notebookId = "nb",
                    pages = listOf(PageCandidate(pageId = 99, title = "Kaufland", text = "# Kaufland", score = 1.0)),
                )
            result.grounded shouldBe true
            // The page leads, with its citation pointing at the page (not a part).
            result.chunks.first().pageId shouldBe 99L
            result.chunks.first().lead shouldBe RetrievalLead.GRAPH
            result.chunks
                .first()
                .citation.sourceRef shouldBe "kallimachos://nb/page/99"
            // The raw part is still present, ranked below the page.
            result.chunks.any { it.partId == 2L } shouldBe true
        }
    })
