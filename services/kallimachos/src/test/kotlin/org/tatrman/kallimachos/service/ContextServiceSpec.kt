package org.tatrman.kallimachos.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kallimachos.adapters.fulltext.InMemoryFullTextAdapter
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter
import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.vector.InMemoryVectorAdapter
import org.tatrman.kallimachos.embeddings.EmbedResult
import org.tatrman.kallimachos.embeddings.EmbeddingsPort
import org.tatrman.kallimachos.ingestion.DocumentParser
import org.tatrman.kallimachos.ingestion.IngestionConfig
import org.tatrman.kallimachos.retrieval.FusionConfig
import org.tatrman.kallimachos.retrieval.GraphWalk
import org.tatrman.kallimachos.retrieval.HybridFusion
import org.tatrman.kallimachos.retrieval.KeywordRecall
import org.tatrman.kallimachos.retrieval.RetrievalLead
import org.tatrman.kallimachos.retrieval.VectorRecall
import org.tatrman.kallimachos.tx.SnapshotTransactor

/**
 * P2 Stage 2.3 T2/T4/T5 — `getContext` end-to-end over the in-memory planes: a
 * graph-primary, cited, mart-scoped result; NO_GROUNDING below threshold; empty
 * mart handled; `findSimilar` vector-led.
 */
class ContextServiceSpec :
    StringSpec({
        // Deterministic embeddings: "synergy" texts → one direction, others → orthogonal.
        val embeddings =
            object : EmbeddingsPort {
                override suspend fun embed(texts: List<String>): EmbedResult {
                    val vecs =
                        texts.map {
                            if (it.contains(
                                    "synergy",
                                    true,
                                )
                            ) {
                                floatArrayOf(1f, 0f)
                            } else {
                                floatArrayOf(0f, 1f)
                            }
                        }
                    return EmbedResult(vecs, "fake", "1", 2)
                }
            }

        val parser = DocumentParser(IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200))

        class Harness(
            minScore: Double,
        ) {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph = InMemoryGraphAdapter()
            val vector = InMemoryVectorAdapter()
            private val tx = SnapshotTransactor(relational, fullText, notebooks, graph)
            val ingestion = IngestionService(relational, fullText, notebooks, graph, tx)
            val embeddingService = EmbeddingService(relational, vector, embeddings, tx)
            val context =
                ContextService(
                    relational = relational,
                    notebooks = notebooks,
                    graph = graph,
                    graphWalk = GraphWalk(graph, relational),
                    vectorRecall = VectorRecall(vector, embeddings),
                    keywordRecall = KeywordRecall(fullText),
                    fusion = HybridFusion(relational, FusionConfig(graphWeight = 1.0, k = 8, minScore = minScore)),
                )
        }

        "getContext returns graph-primary, cited, mart-scoped chunks" {
            val h = Harness(minScore = 0.0)
            h.notebooks.create(NewNotebook("A", "Mart A", "u"))
            h.notebooks.create(NewNotebook("B", "Mart B", "u"))
            val a =
                h.ingestion.ingest(
                    "A",
                    // One paragraph matches the query; the sibling does not — so the
                    // graph CONTAINS-expansion surfaces the sibling as GRAPH-led context.
                    parser.parse(
                        "Quarterly synergy report.\n\nOther unrelated context paragraph.".toByteArray(),
                        "text/plain",
                    ),
                    "text/plain",
                    "A-doc",
                )
            h.ingestion.ingest(
                "B",
                parser.parse("Unrelated beta content.".toByteArray(), "text/plain"),
                "text/plain",
                "B-doc",
            )
            runBlocking { h.embeddingService.embedSource(a.source.id) }

            // vectorBoost off so the keyword hit is the sole seed; the graph then
            // expands CONTAINS to the sibling paragraph (the vector path is covered
            // by findSimilar). In-memory KNN returns every part, which would make
            // every part a seed and leave no expansion in a 2-part fixture.
            val result = runBlocking { h.context.getContext("A", "synergy", k = 8, vectorBoost = false) }
            result.grounded shouldBe true
            result.chunks.isNotEmpty() shouldBe true
            // mart-scoped: every chunk cites mart A's source — no cross-mart leakage.
            result.chunks.all { it.citation.sourceRef.startsWith("kallimachos://A/") } shouldBe true
            result.chunks.all { it.citation.sourceId == a.source.id } shouldBe true
            // The keyword hit grounds; the graph CONTAINS-expansion surfaces the
            // sibling paragraph as GRAPH-led context.
            result.chunks.any { it.lead == RetrievalLead.KEYWORD } shouldBe true
            result.chunks.any { it.lead == RetrievalLead.GRAPH } shouldBe true
        }

        "a query below min-score is NO_GROUNDING (empty, no fabricated chunks)" {
            val h = Harness(minScore = 0.99)
            h.notebooks.create(NewNotebook("A", "Mart A", "u"))
            val a =
                h.ingestion.ingest(
                    "A",
                    parser.parse("Quarterly beta report.".toByteArray(), "text/plain"),
                    "text/plain",
                    "A-doc",
                )
            runBlocking { h.embeddingService.embedSource(a.source.id) }

            // "synergy" matches no keyword in the beta doc; the vector is orthogonal (cosine 0) < 0.99.
            val result = runBlocking { h.context.getContext("A", "synergy", k = 8) }
            result.grounded shouldBe false
            result.chunks.shouldBeEmpty()
        }

        "an empty / unknown mart returns an empty, non-grounded result" {
            val h = Harness(minScore = 0.0)
            val result = runBlocking { h.context.getContext("ghost", "synergy") }
            result.grounded shouldBe false
            result.chunks.shouldBeEmpty()
        }

        "findSimilar is vector-led and mart-scoped" {
            val h = Harness(minScore = 0.0)
            h.notebooks.create(NewNotebook("A", "Mart A", "u"))
            val a =
                h.ingestion.ingest(
                    "A",
                    parser.parse("A synergy paragraph.".toByteArray(), "text/plain"),
                    "text/plain",
                    "A-doc",
                )
            runBlocking { h.embeddingService.embedSource(a.source.id) }

            val result = runBlocking { h.context.findSimilar("A", "synergy", k = 8) }
            result.grounded shouldBe true
            result.chunks.first().lead shouldBe RetrievalLead.VECTOR
        }
    })
