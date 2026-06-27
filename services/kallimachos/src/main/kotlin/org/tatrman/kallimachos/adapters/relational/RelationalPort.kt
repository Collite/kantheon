package org.tatrman.kallimachos.adapters.relational

import org.tatrman.kallimachos.corpus.EmbeddingStatus
import org.tatrman.kallimachos.corpus.PageRecord
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.model.MetadataValue

/**
 * The relational plane (contracts §3 — sources / parts / pages). The only writer
 * of corpus node rows; ids are DB-generated and GLOBALLY UNIQUE across the three
 * families (one shared sequence). Ported in spirit from doc-store's
 * `RelationalPort`, reshaped to the Kleio wiki model.
 *
 * Writes happen inside the caller's [org.tatrman.kallimachos.tx.Transactor]
 * boundary (the one-tx fan-out); these methods do not open their own transaction.
 */
interface RelationalPort {
    fun insertSource(source: NewSource): SourceRecord

    fun insertParts(
        sourceId: Long,
        parts: List<NewPart>,
    ): List<PartRecord>

    fun insertPages(pages: List<NewPage>): List<PageRecord>

    fun getSource(id: Long): SourceRecord?

    fun getPart(id: Long): PartRecord?

    fun getPage(id: Long): PageRecord?

    fun partsOfSource(sourceId: Long): List<PartRecord>

    /** Flip a source's embedding status (the non-atomic embedding edge). */
    fun setEmbeddingStatus(
        sourceId: Long,
        status: EmbeddingStatus,
    )

    /** Source ids still awaiting embeddings — the backfill worklist. */
    fun pendingEmbeddingSourceIds(limit: Int): List<Long>
}

data class NewSource(
    val assetRef: String = "",
    val mimeType: String = "",
    val title: String = "",
    val metadata: Map<String, MetadataValue> = emptyMap(),
    val embeddingStatus: EmbeddingStatus = EmbeddingStatus.PENDING,
)

data class NewPart(
    val idx: Int,
    val kind: String,
    val contentText: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
)

data class NewPage(
    val kind: String,
    val title: String,
    val contentMd: String,
    val conceptRef: String? = null,
)
