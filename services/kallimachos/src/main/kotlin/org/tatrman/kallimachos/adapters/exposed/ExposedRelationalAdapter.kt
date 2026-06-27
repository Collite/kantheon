package org.tatrman.kallimachos.adapters.exposed

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kallimachos.adapters.relational.NewPage
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.corpus.EmbeddingStatus
import org.tatrman.kallimachos.corpus.PageRecord
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.model.MetadataMapSerializer
import org.tatrman.kallimachos.model.MetadataValue
import java.time.OffsetDateTime

/**
 * The live relational adapter on the single Postgres (Exposed 1.0 DSL, not ORM).
 * Runs inside the caller's [org.tatrman.kallimachos.tx.Transactor] boundary —
 * these methods assume an active transaction (the one-tx fan-out). Ids come from
 * the shared `corpus_node_id` sequence, so they are globally unique across
 * sources + parts + pages. Integration-verified (the unit gate uses the
 * in-memory adapter).
 */
class ExposedRelationalAdapter : RelationalPort {
    override fun insertSource(source: NewSource): SourceRecord {
        val id = nextNodeId()
        val now = OffsetDateTime.now()
        Sources.insert {
            it[Sources.id] = id
            it[assetRef] = source.assetRef
            it[mimeType] = source.mimeType
            it[title] = source.title
            it[metadata] = encodeMeta(source.metadata)
            it[embeddingStatus] = source.embeddingStatus.name
            it[createdAt] = now
        }
        return SourceRecord(
            id = id,
            assetRef = source.assetRef,
            mimeType = source.mimeType,
            title = source.title,
            metadata = source.metadata,
            embeddingStatus = source.embeddingStatus,
            createdAt = now.toString(),
        )
    }

    override fun insertParts(
        sourceId: Long,
        parts: List<NewPart>,
    ): List<PartRecord> =
        parts.map { np ->
            val id = nextNodeId()
            Parts.insert {
                it[Parts.id] = id
                it[Parts.sourceId] = sourceId
                it[idx] = np.idx
                it[kind] = np.kind
                it[contentText] = np.contentText
                it[metadata] = encodeMeta(np.metadata)
            }
            PartRecord(
                id = id,
                sourceId = sourceId,
                idx = np.idx,
                kind = np.kind,
                contentText = np.contentText,
                metadata = np.metadata,
            )
        }

    override fun insertPages(pages: List<NewPage>): List<PageRecord> =
        pages.map { np ->
            val id = nextNodeId()
            val now = OffsetDateTime.now()
            Pages.insert {
                it[Pages.id] = id
                it[kind] = np.kind
                it[title] = np.title
                it[contentMd] = np.contentMd
                it[conceptRef] = np.conceptRef
                it[updatedAt] = now
            }
            PageRecord(
                id = id,
                kind = np.kind,
                title = np.title,
                contentMd = np.contentMd,
                conceptRef = np.conceptRef,
                updatedAt = now.toString(),
            )
        }

    override fun getSource(id: Long): SourceRecord? =
        Sources
            .selectAll()
            .where { Sources.id eq id }
            .singleOrNull()
            ?.toSource()

    override fun getPart(id: Long): PartRecord? =
        Parts
            .selectAll()
            .where { Parts.id eq id }
            .singleOrNull()
            ?.toPart()

    override fun getPage(id: Long): PageRecord? =
        Pages
            .selectAll()
            .where { Pages.id eq id }
            .singleOrNull()
            ?.toPage()

    override fun partsOfSource(sourceId: Long): List<PartRecord> =
        Parts
            .selectAll()
            .where { Parts.sourceId eq sourceId }
            .orderBy(Parts.idx)
            .map { it.toPart() }

    override fun setEmbeddingStatus(
        sourceId: Long,
        status: EmbeddingStatus,
    ) {
        Sources.update({ Sources.id eq sourceId }) { it[embeddingStatus] = status.name }
    }

    override fun pendingEmbeddingSourceIds(limit: Int): List<Long> =
        Sources
            .selectAll()
            .where { Sources.embeddingStatus eq EmbeddingStatus.PENDING.name }
            .limit(limit)
            .map { it[Sources.id] }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun nextNodeId(): Long =
        TransactionManager.current().exec("SELECT nextval('corpus_node_id') AS id") { rs ->
            rs.next()
            rs.getLong("id")
        } ?: error("corpus_node_id sequence returned no value")

    private fun encodeMeta(m: Map<String, MetadataValue>): String = Json.encodeToString(MetadataMapSerializer, m)

    private fun decodeMeta(s: String?): Map<String, MetadataValue> =
        if (s.isNullOrBlank()) emptyMap() else Json.decodeFromString(MetadataMapSerializer, s)

    private fun ResultRow.toSource() =
        SourceRecord(
            id = this[Sources.id],
            assetRef = this[Sources.assetRef],
            mimeType = this[Sources.mimeType],
            title = this[Sources.title],
            metadata = decodeMeta(this[Sources.metadata]),
            embeddingStatus =
                runCatching {
                    EmbeddingStatus.valueOf(this[Sources.embeddingStatus])
                }.getOrDefault(EmbeddingStatus.PENDING),
            createdAt = this[Sources.createdAt].toString(),
        )

    private fun ResultRow.toPart() =
        PartRecord(
            id = this[Parts.id],
            sourceId = this[Parts.sourceId],
            idx = this[Parts.idx],
            kind = this[Parts.kind],
            contentText = this[Parts.contentText],
            metadata = decodeMeta(this[Parts.metadata]),
        )

    private fun ResultRow.toPage() =
        PageRecord(
            id = this[Pages.id],
            kind = this[Pages.kind],
            title = this[Pages.title],
            contentMd = this[Pages.contentMd],
            conceptRef = this[Pages.conceptRef],
            updatedAt = this[Pages.updatedAt].toString(),
        )
}
