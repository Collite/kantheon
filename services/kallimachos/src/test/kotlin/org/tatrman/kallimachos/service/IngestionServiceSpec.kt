package org.tatrman.kallimachos.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.fulltext.FullTextQuery
import org.tatrman.kallimachos.adapters.fulltext.InMemoryFullTextAdapter
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter
import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.ingestion.DocumentParser
import org.tatrman.kallimachos.ingestion.IngestionConfig
import org.tatrman.kallimachos.tx.SnapshotStore
import org.tatrman.kallimachos.tx.SnapshotTransactor

/**
 * P1 Stage 1.2 → P2 Stage 2.2 — the ingestion fan-out (architecture §13). A
 * `DocNode` fans out to the relational + full-text + graph (`CONTAINS`) planes
 * inside ONE transaction; a throw in any plane rolls the WHOLE fan-out back,
 * leaving nothing. (The vector plane is the non-atomic edge, embedded
 * out-of-band — not part of this transaction.)
 */
class IngestionServiceSpec :
    StringSpec({
        val parser = DocumentParser(IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200))
        val doc = "Alpha first paragraph.\n\nBeta second paragraph.\n\nGamma third paragraph."

        fun fixtureNotebook(notebooks: InMemoryNotebookAdapter) =
            notebooks.create(NewNotebook(id = "nb1", displayName = "Mart", ownerUserId = "u")).id

        "ingest fans out source + parts + CONTAINS edges into the mart" {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph = InMemoryGraphAdapter()
            val tx = SnapshotTransactor(relational, fullText, notebooks, graph)
            val service = IngestionService(relational, fullText, notebooks, graph, tx)
            val nb = fixtureNotebook(notebooks)

            val result = service.ingest(nb, parser.parse(doc.toByteArray(), "text/plain"), "text/plain", "Doc")

            result.parts.size shouldBe 3
            notebooks.memberSourceIds(nb) shouldBe setOf(result.source.id)
            fullText.search(FullTextQuery(text = "Beta"), setOf(result.source.id)).isNotEmpty() shouldBe true
            // Four planes: the structural CONTAINS edges link the source to its parts.
            val contains = graph.neighbors(GraphNodeRef("source", result.source.id), setOf(GraphEdgeKind.CONTAINS))
            contains.size shouldBe 3
            contains.map { it.to.id }.toSet() shouldBe result.parts.map { it.id }.toSet()
        }

        "ingest into a non-existent mart is rejected (scope mandatory)" {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph = InMemoryGraphAdapter()
            val service =
                IngestionService(
                    relational,
                    fullText,
                    notebooks,
                    graph,
                    SnapshotTransactor(relational, fullText, notebooks, graph),
                )

            shouldThrow<NotebookNotFoundException> {
                service.ingest("ghost", parser.parse(doc.toByteArray(), "text/plain"), "text/plain", "Doc")
            }
        }

        "a failure at the LAST fan-out step rolls all four planes back" {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val graph = InMemoryGraphAdapter()
            val realNotebooks = InMemoryNotebookAdapter()
            // The notebook plane throws on the final membership write — by then
            // relational + fulltext + graph have all written; the rollback must
            // undo every one of them.
            val explodingNotebooks =
                object : org.tatrman.kallimachos.adapters.notebook.NotebookPort by realNotebooks, SnapshotStore {
                    override fun addMember(
                        notebookId: String,
                        nodeKind: org.tatrman.kallimachos.corpus.NodeKind,
                        nodeId: Long,
                    ): Unit = throw IllegalStateException("boom")

                    override fun snapshot(): () -> Unit = realNotebooks.snapshot()
                }
            val tx = SnapshotTransactor(relational, fullText, explodingNotebooks, graph)
            val service = IngestionService(relational, fullText, explodingNotebooks, graph, tx)
            val nb = realNotebooks.create(NewNotebook(id = "nb1", displayName = "Mart", ownerUserId = "u")).id

            shouldThrow<IllegalStateException> {
                service.ingest(nb, parser.parse(doc.toByteArray(), "text/plain"), "text/plain", "Doc")
            }

            // All four planes rolled back — the source, its parts, its CONTAINS
            // edges, and the (would-be) membership are all gone.
            relational.getSource(1L).shouldBeNull()
            relational.partsOfSource(1L).shouldBeEmpty()
            fullText.search(FullTextQuery(text = "Beta"), null).shouldBeEmpty()
            graph.neighbors(GraphNodeRef("source", 1L), emptySet()).shouldBeEmpty()
            realNotebooks.memberSourceIds(nb).shouldBeEmpty()
        }
    })
