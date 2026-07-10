package org.tatrman.kallimachos.http

import kotlinx.serialization.Serializable
import org.tatrman.kallimachos.corpus.NotebookRecord
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.model.MetadataList
import org.tatrman.kallimachos.model.MetadataSingle
import org.tatrman.kallimachos.model.MetadataValue
import org.tatrman.kallimachos.service.QueryHit

/**
 * REST DTOs for the corpus surface (P1 Stage 1.2). JSON mirrors of the proto
 * (contracts §1, camelCase); the proto remains the source of truth (Wire policy)
 * and CI tests the REST shapes against it. `metadataJson` rides as a Rule-7 JSON
 * string (the metadata map is parsed via `parseMetadataJson`).
 */

@Serializable
data class IngestRequest(
    val notebookId: String,
    val mimeType: String = "text/plain",
    val title: String = "",
    val contentText: String = "",
    val assetRef: String = "",
    val metadataJson: String? = null,
)

@Serializable
data class IngestResponse(
    val sourceId: Long,
    val title: String,
    val partCount: Int,
    val partIds: List<Long>,
)

@Serializable
data class NotebookCreateRequest(
    val displayName: String,
    val ownerUserId: String? = null,
    val visibilityRoles: List<String> = emptyList(),
    // Optional explicit id (ops/admin curation + Pinakes feed marts). Server
    // assigns a UUID when absent.
    val id: String? = null,
)

@Serializable
data class NotebookDto(
    val id: String,
    val displayName: String,
    val ownerUserId: String,
    val visibilityRoles: List<String>,
    val memberCount: Long,
)

@Serializable
data class QueryRequest(
    val notebookId: String,
    val text: String? = null,
    val keywords: List<String> = emptyList(),
    val metadataJson: String? = null,
    val limit: Int = 10,
)

@Serializable
data class HitDto(
    val id: Long,
    val kind: String,
    val score: Double,
    val snippet: String,
    val metadata: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class PartDto(
    val id: Long,
    val sourceId: Long,
    val idx: Int,
    val kind: String,
    val contentText: String,
)

@Serializable
data class SourceDto(
    val id: Long,
    val assetRef: String,
    val mimeType: String,
    val title: String,
    val embeddingStatus: String,
    val parts: List<PartDto> = emptyList(),
)

@Serializable
data class ErrorDto(
    val error: String,
    val detail: String? = null,
)

/**
 * Internal LoadApi request (Pinakes-only, cluster-internal — contracts §1 the
 * `Load*Request` surface). Pinakes has already extracted + chunked, so it sends
 * pre-split `parts`; `notebookId` binds the loaded source into a mart so the
 * stage→warehouse path is queryable.
 */
@Serializable
data class LoadSourceDto(
    val notebookId: String,
    val title: String = "",
    val mimeType: String = "text/plain",
    val assetRef: String = "",
    val parts: List<String> = emptyList(),
    val metadataJson: String? = null,
)

@Serializable
data class LoadSourceResponse(
    val sourceId: Long,
    val partCount: Int,
    val partIds: List<Long>,
)

@Serializable
data class LoadConceptRefDto(
    val entityType: String,
    val entityId: String,
    val displayLabel: String,
    val velesQname: String = "",
)

@Serializable
data class LoadPageDto(
    val localId: Int,
    val kind: String,
    val title: String,
    val contentMd: String,
    val derivedFromParts: List<Long> = emptyList(),
    val conceptRef: LoadConceptRefDto? = null,
)

@Serializable
data class LoadLinkDto(
    val fromLocalId: Int,
    val toLocalId: Int,
    val edgeKind: String,
)

@Serializable
data class LoadPagesDto(
    val notebookId: String,
    val sourceId: Long,
    val pages: List<LoadPageDto> = emptyList(),
    val links: List<LoadLinkDto> = emptyList(),
)

@Serializable
data class LoadPagesResponse(
    val pageIds: List<Long>,
)

@Serializable
data class ContextRequestDto(
    val notebookId: String,
    val query: String,
    val k: Int = 8,
    val graphHops: Int = 2,
    val vectorBoost: Boolean = true,
)

@Serializable
data class FindSimilarRequestDto(
    val notebookId: String,
    val query: String,
    val k: Int = 8,
)

@Serializable
data class CitationDto(
    val sourceId: Long,
    val partId: Long,
    val pageId: Long? = null,
    val title: String,
    val locator: String,
    val sourceRef: String,
)

@Serializable
data class ContextChunkDto(
    val partId: Long,
    val sourceId: Long,
    val pageId: Long? = null,
    val text: String,
    val score: Double,
    val lead: String,
    val citation: CitationDto,
)

@Serializable
data class ContextResponseDto(
    val chunks: List<ContextChunkDto>,
    val grounded: Boolean,
)

internal fun NotebookRecord.toDto() = NotebookDto(id, displayName, ownerUserId, visibilityRoles, memberCount)

internal fun QueryHit.toDto() = HitDto(id, kind, score, snippet, metadata.flatten())

internal fun PartRecord.toDto() = PartDto(id, sourceId, idx, kind, contentText)

internal fun SourceRecord.toDto(parts: List<PartRecord>) =
    SourceDto(id, assetRef, mimeType, title, embeddingStatus.name, parts.map { it.toDto() })

internal fun Map<String, MetadataValue>.flatten(): Map<String, List<String>> =
    mapValues { (_, v) ->
        when (v) {
            is MetadataSingle -> listOf(v.value)
            is MetadataList -> v.values
        }
    }

internal fun org.tatrman.kallimachos.retrieval.Citation.toDto() =
    CitationDto(sourceId, partId, pageId, title, locator, sourceRef)

internal fun org.tatrman.kallimachos.retrieval.RetrievedChunk.toDto() =
    ContextChunkDto(partId, sourceId, pageId, text, score, lead.name, citation.toDto())

internal fun org.tatrman.kallimachos.retrieval.ContextResult.toDto() =
    ContextResponseDto(chunks.map { it.toDto() }, grounded)
