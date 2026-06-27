package org.tatrman.kallimachos.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.fulltext.InMemoryFullTextAdapter
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter
import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.ingestion.DocumentParser
import org.tatrman.kallimachos.model.MetadataSingle

/**
 * P1 Stage 1.2 T7 — the mart-scoped keyword `query`. Hits come only from the
 * scoped mart (no cross-mart leakage); metadata narrows; `limit` defaults to 10.
 */
class DocumentQueryServiceSpec :
    StringSpec({
        val parser = DocumentParser()

        // Two marts, each with its own doc mentioning "synergy". A query scoped to
        // mart A must never surface mart B's parts.
        fun fixture(): Triple<DocumentQueryService, String, String> {
            val relational = InMemoryRelationalAdapter()
            val fullText = InMemoryFullTextAdapter()
            val notebooks = InMemoryNotebookAdapter()
            val graph =
                org.tatrman.kallimachos.adapters.graph
                    .InMemoryGraphAdapter()
            val ingestion =
                IngestionService(
                    relational,
                    fullText,
                    notebooks,
                    graph,
                    org.tatrman.kallimachos.tx
                        .SnapshotTransactor(relational, fullText, notebooks, graph),
                )
            val a = notebooks.create(NewNotebook("A", "Mart A", "u")).id
            val b = notebooks.create(NewNotebook("B", "Mart B", "u")).id
            ingestion.ingest(
                a,
                parser.parse("Quarterly synergy report for alpha team.".toByteArray(), "text/plain"),
                "text/plain",
                "A-doc",
                mapOf(
                    "dept" to MetadataSingle("alpha"),
                ),
            )
            ingestion.ingest(
                b,
                parser.parse("Quarterly synergy report for beta team.".toByteArray(), "text/plain"),
                "text/plain",
                "B-doc",
                mapOf(
                    "dept" to MetadataSingle("beta"),
                ),
            )
            return Triple(DocumentQueryService(relational, fullText, notebooks), a, b)
        }

        "a query is scoped to its mart — no cross-mart leakage" {
            val (queries, a, _) = fixture()
            val hits = queries.query(QuerySpec(notebookId = a, text = "synergy"))
            hits.isNotEmpty() shouldBe true
            // every hit's snippet is from mart A's doc (alpha), never beta
            hits.all { it.snippet.contains("alpha") } shouldBe true
            hits.none { it.snippet.contains("beta") } shouldBe true
        }

        "metadata filter narrows within the mart" {
            val (queries, a, _) = fixture()
            queries
                .query(
                    QuerySpec(
                        notebookId = a,
                        text = "synergy",
                        metadataFilter =
                            mapOf(
                                "dept" to MetadataSingle("alpha"),
                            ),
                    ),
                ).isNotEmpty() shouldBe
                true
            queries
                .query(
                    QuerySpec(
                        notebookId = a,
                        text = "synergy",
                        metadataFilter =
                            mapOf(
                                "dept" to MetadataSingle("beta"),
                            ),
                    ),
                ).shouldBeEmpty()
        }

        "a query against an empty / unknown mart returns nothing" {
            val (queries, _, _) = fixture()
            queries.query(QuerySpec(notebookId = "ghost", text = "synergy")).shouldBeEmpty()
        }

        "limit defaults to 10" {
            QuerySpec(notebookId = "x").limit shouldBe 10
        }
    })
