package org.tatrman.kallimachos.corpus

import org.tatrman.kallimachos.model.MetadataValue

/**
 * Internal corpus domain records (decoupled from the proto wire types — the
 * proto is the boundary contract, these are the in-process model). Mirror
 * contracts §1/§3. Mapping to/from the proto happens at the HTTP edge.
 */

enum class EmbeddingStatus { OK, PENDING }

/** A corpus node family — the unit of mart membership + citation. */
enum class NodeKind { SOURCE, PART, PAGE }

data class SourceRecord(
    val id: Long,
    val assetRef: String,
    val mimeType: String,
    val title: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
    val embeddingStatus: EmbeddingStatus = EmbeddingStatus.PENDING,
    val createdAt: String = "",
)

data class PartRecord(
    val id: Long,
    val sourceId: Long,
    val idx: Int,
    val kind: String,
    val contentText: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
)

data class PageRecord(
    val id: Long,
    val kind: String,
    val title: String,
    val contentMd: String,
    val conceptRef: String? = null, // §6 seam — JSON blob, null at v1
    val updatedAt: String = "",
)

data class NotebookRecord(
    val id: String,
    val displayName: String,
    val ownerUserId: String,
    val visibilityRoles: List<String> = emptyList(),
    val memberCount: Long = 0,
    val createdAt: String = "",
)
