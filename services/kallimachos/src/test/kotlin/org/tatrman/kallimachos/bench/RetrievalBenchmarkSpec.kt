package org.tatrman.kallimachos.bench

import io.kotest.core.spec.style.StringSpec
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
import org.tatrman.kallimachos.retrieval.GraphWalk
import org.tatrman.kallimachos.retrieval.KeywordRecall
import org.tatrman.kallimachos.retrieval.VectorRecall
import org.tatrman.kallimachos.service.EmbeddingService
import org.tatrman.kallimachos.service.IngestionService
import org.tatrman.kallimachos.tx.SnapshotTransactor

/**
 * P2 Stage 2.3 T6 — the retrieval benchmark emits per-plane candidate counts +
 * latency on a reference mart (feeds P4 `cost_hints`). Deterministic via an
 * injected clock.
 */
class RetrievalBenchmarkSpec :
    StringSpec({
        val embeddings =
            object : EmbeddingsPort {
                override suspend fun embed(texts: List<String>): EmbedResult =
                    EmbedResult(
                        texts.map { if (it.contains("synergy", true)) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) },
                        "f",
                        "1",
                        2,
                    )
            }
        val parser = DocumentParser(IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200))

        "the benchmark reports candidates + latency per plane" {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph = InMemoryGraphAdapter()
            val vector = InMemoryVectorAdapter()
            val tx = SnapshotTransactor(relational, fullText, notebooks, graph)
            val ingestion = IngestionService(relational, fullText, notebooks, graph, tx)
            val embeddingService = EmbeddingService(relational, vector, embeddings, tx)
            notebooks.create(NewNotebook("A", "Mart A", "u"))
            val a =
                ingestion.ingest(
                    "A",
                    parser.parse("Quarterly synergy report.\n\nMore synergy detail here.".toByteArray(), "text/plain"),
                    "text/plain",
                    "A-doc",
                )
            runBlocking { embeddingService.embedSource(a.source.id) }

            // Monotone fake clock — each read advances 5ms, so latencies are positive + deterministic.
            var now = 0L
            val clock = {
                now += 5
                now
            }
            val bench =
                RetrievalBenchmark(
                    KeywordRecall(fullText),
                    VectorRecall(vector, embeddings),
                    GraphWalk(graph, relational),
                    relational,
                    notebooks,
                    clock,
                )

            val report = runBlocking { bench.run("A", "synergy", k = 8, graphHops = 2) }
            report.planes.map { it.plane } shouldBe listOf("keyword", "vector", "graph")
            report.candidatesFor("keyword") shouldBe 2 // both "synergy" paragraphs match
            report.candidatesFor("vector") shouldBe 2
            report.graphWalkDepth shouldBe 2
            report.planes.all { it.latencyMs > 0 } shouldBe true
        }
    })
