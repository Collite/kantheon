package org.tatrman.kallimachos.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.GraphPort
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.NewPage
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.corpus.NodeKind
import org.tatrman.kallimachos.tx.Transactor

/** A page to load + its provenance/edges (mirrors the LoadApi `LoadPagesRequest`). */
data class PageLoad(
    val localId: Int,
    val kind: String,
    val title: String,
    val contentMd: String,
    val derivedFromParts: List<Long>,
    val conceptRef: ConceptRefInput? = null,
)

data class ConceptRefInput(
    val entityType: String,
    val entityId: String,
    val displayLabel: String,
    val velesQname: String = "",
)

data class LinkLoad(
    val fromLocalId: Int,
    val toLocalId: Int,
    val edgeKind: String,
)

/**
 * The LoadApi page write (contracts §1 `LoadPagesRequest`) — Pinakes-only, the
 * P3 compile's warehouse landing. In ONE transaction: insert the LLM-authored
 * pages, write `DERIVED_FROM` provenance (page → source parts), the page↔page
 * content edges, and the mart membership. The pages join the wiki-graph so
 * graph-primary retrieval (S3.2 T7) can lead with them.
 */
class PageLoadService(
    private val relational: RelationalPort,
    private val graph: GraphPort,
    private val notebooks: NotebookPort,
    private val transactor: Transactor,
) {
    fun loadPages(
        notebookId: String,
        pages: List<PageLoad>,
        links: List<LinkLoad>,
    ): List<Long> {
        if (pages.isEmpty()) return emptyList()
        return transactor.inTransaction {
            val records =
                relational.insertPages(
                    pages.map { p ->
                        NewPage(
                            kind = p.kind,
                            title = p.title,
                            contentMd = p.contentMd,
                            conceptRef =
                                p.conceptRef?.let {
                                    encodeConceptRef(it)
                                },
                        )
                    },
                )
            val localToId = pages.mapIndexed { i, p -> p.localId to records[i].id }.toMap()

            pages.forEachIndexed { i, p ->
                val pageId = records[i].id
                val pageNode = GraphNodeRef("page", pageId)
                graph.upsertNode(pageNode)
                p.derivedFromParts.forEach { partId ->
                    graph.relate(pageNode, GraphNodeRef("part", partId), GraphEdgeKind.DERIVED_FROM)
                }
                notebooks.addMember(notebookId, NodeKind.PAGE, pageId)
            }

            links.forEach { l ->
                val from = localToId[l.fromLocalId]
                val to = localToId[l.toLocalId]
                if (from != null && to != null) {
                    val kind = runCatching { GraphEdgeKind.valueOf(l.edgeKind) }.getOrDefault(GraphEdgeKind.RELATED)
                    graph.relate(GraphNodeRef("page", from), GraphNodeRef("page", to), kind)
                }
            }
            records.map { it.id }
        }
    }

    private fun encodeConceptRef(ref: ConceptRefInput): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("entityType", JsonPrimitive(ref.entityType))
                put("entityId", JsonPrimitive(ref.entityId))
                put("displayLabel", JsonPrimitive(ref.displayLabel))
                put("velesQname", JsonPrimitive(ref.velesQname))
            },
        )
}
