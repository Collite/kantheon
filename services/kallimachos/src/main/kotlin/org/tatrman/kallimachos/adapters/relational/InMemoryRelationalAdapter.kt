package org.tatrman.kallimachos.adapters.relational

import org.tatrman.kallimachos.corpus.PageRecord
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.tx.SnapshotStore
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory relational plane. The wired adapter for the single-PG-not-yet-live
 * profile (P1 runs in-process; the Exposed/PG adapter is integration-verified
 * and switched in at deploy), and the test fake the service specs exercise.
 *
 * The shared [idSeq] mirrors the `corpus_node_id` sequence — one counter across
 * sources + parts + pages, so node ids are globally unique. Implements
 * [SnapshotStore] so the one-tx rollback invariant is provable with fakes.
 */
class InMemoryRelationalAdapter :
    RelationalPort,
    SnapshotStore {
    private val idSeq = AtomicLong(0)
    private val sources = linkedMapOf<Long, SourceRecord>()
    private val parts = linkedMapOf<Long, PartRecord>()
    private val pages = linkedMapOf<Long, PageRecord>()

    override fun insertSource(source: NewSource): SourceRecord {
        val id = idSeq.incrementAndGet()
        val rec =
            SourceRecord(
                id = id,
                assetRef = source.assetRef,
                mimeType = source.mimeType,
                title = source.title,
                metadata = source.metadata,
                embeddingStatus = source.embeddingStatus,
            )
        sources[id] = rec
        return rec
    }

    override fun insertParts(
        sourceId: Long,
        parts: List<NewPart>,
    ): List<PartRecord> =
        parts.map { np ->
            val id = idSeq.incrementAndGet()
            val rec =
                PartRecord(
                    id = id,
                    sourceId = sourceId,
                    idx = np.idx,
                    kind = np.kind,
                    contentText = np.contentText,
                    metadata = np.metadata,
                )
            this.parts[id] = rec
            rec
        }

    override fun insertPages(pages: List<NewPage>): List<PageRecord> =
        pages.map { np ->
            val id = idSeq.incrementAndGet()
            val rec =
                PageRecord(
                    id = id,
                    kind = np.kind,
                    title = np.title,
                    contentMd = np.contentMd,
                    conceptRef = np.conceptRef,
                )
            this.pages[id] = rec
            rec
        }

    override fun getSource(id: Long): SourceRecord? = sources[id]

    override fun getPart(id: Long): PartRecord? = parts[id]

    override fun getPage(id: Long): PageRecord? = pages[id]

    override fun partsOfSource(sourceId: Long): List<PartRecord> = parts.values.filter { it.sourceId == sourceId }

    override fun setEmbeddingStatus(
        sourceId: Long,
        status: org.tatrman.kallimachos.corpus.EmbeddingStatus,
    ) {
        sources[sourceId]?.let { sources[sourceId] = it.copy(embeddingStatus = status) }
    }

    override fun pendingEmbeddingSourceIds(limit: Int): List<Long> =
        sources.values
            .filter { it.embeddingStatus == org.tatrman.kallimachos.corpus.EmbeddingStatus.PENDING }
            .map { it.id }
            .take(limit)

    override fun snapshot(): () -> Unit {
        val s = LinkedHashMap(sources)
        val p = LinkedHashMap(parts)
        val g = LinkedHashMap(pages)
        return {
            sources.clear()
            sources.putAll(s)
            parts.clear()
            parts.putAll(p)
            pages.clear()
            pages.putAll(g)
        }
    }
}
