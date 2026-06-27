package org.tatrman.kallimachos.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.InMemoryGraphAdapter
import org.tatrman.kallimachos.adapters.notebook.InMemoryNotebookAdapter
import org.tatrman.kallimachos.adapters.notebook.NewNotebook
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.tx.SnapshotTransactor

/**
 * P3 Stage 3.2 T2/T4 — the LoadApi page write: compiled pages land in the corpus
 * with `DERIVED_FROM` provenance (page → source parts), page↔page content edges,
 * and mart membership, so graph-primary retrieval can lead with them.
 */
class PageLoadServiceSpec :
    StringSpec({
        "loadPages writes pages, DERIVED_FROM provenance, content edges + mart membership" {
            val relational = InMemoryRelationalAdapter()
            val graph = InMemoryGraphAdapter()
            val notebooks = InMemoryNotebookAdapter()
            notebooks.create(NewNotebook("nb", "Mart", "u"))
            val service =
                PageLoadService(relational, graph, notebooks, SnapshotTransactor(relational, graph, notebooks))

            // Two pages (a SUMMARY localId 0, an ENTITY localId 1) derived from parts 5,6.
            val pageIds =
                service.loadPages(
                    notebookId = "nb",
                    pages =
                        listOf(
                            PageLoad(0, "SUMMARY", "Overview", "...", listOf(5, 6)),
                            PageLoad(
                                1,
                                "ENTITY",
                                "Kaufland",
                                "# Kaufland",
                                listOf(5),
                                ConceptRefInput("customer", "wiki:kaufland", "Kaufland"),
                            ),
                        ),
                    links = listOf(LinkLoad(0, 1, "MENTIONS")),
                )

            pageIds.size shouldBe 2
            val (summaryId, entityId) = pageIds
            // Pages persisted; the §6 concept_ref seam is stored on the ENTITY page.
            relational.getPage(entityId)!!.title shouldBe "Kaufland"
            relational.getPage(entityId)!!.conceptRef!!.contains("wiki:kaufland") shouldBe true

            // DERIVED_FROM: the SUMMARY page → parts 5,6.
            graph.neighbors(GraphNodeRef("page", summaryId), setOf(GraphEdgeKind.DERIVED_FROM)).map {
                it.to.id
            } shouldContainExactlyInAnyOrder
                listOf(5L, 6L)
            // Content edge SUMMARY → ENTITY: MENTIONS.
            graph.neighbors(GraphNodeRef("page", summaryId), setOf(GraphEdgeKind.MENTIONS)).map {
                it.to.id
            } shouldContainExactlyInAnyOrder
                listOf(entityId)
            // Both pages are members of the mart.
            notebooks.memberCount("nb") shouldBe 2
        }
    })
