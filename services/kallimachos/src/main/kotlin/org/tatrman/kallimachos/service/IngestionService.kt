package org.tatrman.kallimachos.service

import org.tatrman.kallimachos.adapters.fulltext.FullTextPort
import org.tatrman.kallimachos.adapters.fulltext.IndexedPart
import org.tatrman.kallimachos.adapters.graph.GraphEdgeKind
import org.tatrman.kallimachos.adapters.graph.GraphNodeRef
import org.tatrman.kallimachos.adapters.graph.GraphPort
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.corpus.NodeKind
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.ingestion.DocNode
import org.tatrman.kallimachos.ingestion.toParts
import org.tatrman.kallimachos.model.MetadataValue
import org.tatrman.kallimachos.tx.Transactor

/** Thrown when ingestion targets a mart that does not exist (scope is mandatory). */
class NotebookNotFoundException(
    val notebookId: String,
) : RuntimeException("notebook not found: $notebookId")

data class IngestResult(
    val source: SourceRecord,
    val parts: List<PartRecord>,
)

/**
 * Mechanical ingestion — one `DocNode` fans out to the relational + full-text +
 * graph (`CONTAINS`) planes inside ONE transaction (architecture §13). A throw in
 * any plane rolls the whole fan-out back, leaving nothing. The vector plane is
 * the one non-atomic edge (out-of-band embedding → `embedding_status = PENDING` +
 * backfill, [EmbeddingService]), so it is NOT in this transaction. The source is
 * added to the target mart; `notebook_id` is REQUIRED (contracts §7).
 */
class IngestionService(
    private val relational: RelationalPort,
    private val fullText: FullTextPort,
    private val notebooks: NotebookPort,
    private val graph: GraphPort,
    private val transactor: Transactor,
) {
    fun ingest(
        notebookId: String,
        root: DocNode,
        mimeType: String,
        title: String,
        metadata: Map<String, MetadataValue> = emptyMap(),
        assetRef: String = "",
    ): IngestResult = ingestParts(notebookId, root.toParts(), mimeType, title, metadata, assetRef)

    /**
     * The shared fan-out over already-chunked parts. `/documents` parses then
     * calls this; the internal `LoadApi` (Pinakes mechanical pipeline, Stage 1.3)
     * calls it with parts Pinakes already extracted + chunked.
     */
    fun ingestParts(
        notebookId: String,
        partTexts: List<String>,
        mimeType: String,
        title: String,
        metadata: Map<String, MetadataValue> = emptyMap(),
        assetRef: String = "",
    ): IngestResult {
        if (notebooks.get(notebookId) == null) throw NotebookNotFoundException(notebookId)

        return transactor.inTransaction {
            val source =
                relational.insertSource(
                    NewSource(
                        assetRef = assetRef,
                        mimeType = mimeType,
                        title = title,
                        metadata = metadata,
                    ),
                )
            val parts =
                relational.insertParts(
                    source.id,
                    partTexts.mapIndexed { i, text ->
                        NewPart(idx = i, kind = "paragraph", contentText = text, metadata = metadata)
                    },
                )
            parts.forEach { fullText.index(IndexedPart(it.id, source.id, it.contentText, metadata)) }

            // GRAPH plane — the structural CONTAINS edges (Source → Part), the
            // fourth plane of the atomic fan-out. The content links
            // (MENTIONS/ABOUT/RELATED/…) are authored by the P3 compile.
            val sourceNode = GraphNodeRef("source", source.id)
            graph.upsertNode(sourceNode)
            parts.forEach { p ->
                val partNode = GraphNodeRef("part", p.id)
                graph.upsertNode(partNode)
                graph.relate(sourceNode, partNode, GraphEdgeKind.CONTAINS, weight = p.idx.toDouble())
            }

            notebooks.addMember(notebookId, NodeKind.SOURCE, source.id)
            IngestResult(source, parts)
        }
    }
}
