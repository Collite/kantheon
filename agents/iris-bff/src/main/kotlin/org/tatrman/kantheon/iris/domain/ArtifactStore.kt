package org.tatrman.kantheon.iris.domain

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Artifact kind (`iris_artifacts.kind`, contracts §3.3). */
enum class ArtifactKind(
    val wire: String,
) {
    PIN("pin"),
    DASHBOARD("dashboard"),
    ;

    companion object {
        fun fromWire(s: String): ArtifactKind =
            entries.firstOrNull { it.wire == s }
                ?: error("unknown ArtifactKind '$s' (expected one of ${entries.map { it.wire }})")
    }
}

/**
 * One `iris_artifacts` row (PD-6, contracts §3.3). A **pin** is a saved,
 * refreshable view of an envelope (snapshot + ViewProvenance + applied_context +
 * BFF display state); a **dashboard** is a named, ordered collection of pins +
 * `layout_json` (+ optional domain `template_id`). JSON columns are opaque JSON
 * strings (the route layer owns proto/DTO shaping).
 */
data class ArtifactRecord(
    val artifactId: UUID,
    val userId: String,
    val tenantId: String,
    val kind: ArtifactKind,
    val name: String,
    val agentId: String? = null,
    val envelopeJson: String? = null,
    val provenanceJson: String? = null,
    val appliedContextJson: String? = null,
    val displayStateJson: String? = null,
    val paramsJson: String? = null,
    val refreshMode: String = "manual",
    val paramMode: String? = null,
    val templateId: String? = null,
    val memberIds: List<UUID> = emptyList(),
    val layoutJson: String? = null,
    val refreshedAt: Instant? = null,
    val refreshError: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Fields a caller supplies to create an artifact; ids/timestamps are store-assigned. */
data class NewArtifact(
    val userId: String,
    val tenantId: String,
    val kind: ArtifactKind,
    val name: String,
    val agentId: String? = null,
    val envelopeJson: String? = null,
    val provenanceJson: String? = null,
    val appliedContextJson: String? = null,
    val displayStateJson: String? = null,
    val paramsJson: String? = null,
    val refreshMode: String = "manual",
    val paramMode: String? = null,
    val templateId: String? = null,
    val memberIds: List<UUID> = emptyList(),
    val layoutJson: String? = null,
)

/** Mutable fields for `PATCH /v1/artifacts/{id}` (null = leave unchanged). */
data class ArtifactPatch(
    val name: String? = null,
    val paramsJson: String? = null,
    val layoutJson: String? = null,
    val memberIds: List<UUID>? = null,
    val refreshMode: String? = null,
)

/**
 * Artifact persistence (PD-6, contracts §3.3). The interface is the behavioural
 * contract; [InMemoryArtifactStore] backs the unit/component gate and
 * `ExposedArtifactStore` the Postgres binding (integration-deferred per
 * planning-conventions §4). Ownership is enforced at the route layer — these
 * methods take the `userId` for scoping but assume the id has been owner-checked.
 */
interface ArtifactStore {
    fun create(artifact: NewArtifact): ArtifactRecord

    fun get(artifactId: UUID): ArtifactRecord?

    fun list(
        userId: String,
        kind: ArtifactKind? = null,
    ): List<ArtifactRecord>

    fun patch(
        artifactId: UUID,
        patch: ArtifactPatch,
    ): ArtifactRecord?

    fun delete(artifactId: UUID): Boolean

    /** Record a refresh outcome: timestamp + optional error + the new envelope snapshot. */
    fun recordRefresh(
        artifactId: UUID,
        refreshedAt: Instant,
        envelopeJson: String?,
        refreshError: String?,
    ): ArtifactRecord?
}

/** In-memory [ArtifactStore] — the unit/component-test fake. */
class InMemoryArtifactStore : ArtifactStore {
    private val rows = ConcurrentHashMap<UUID, ArtifactRecord>()

    override fun create(artifact: NewArtifact): ArtifactRecord {
        val now = Instant.now()
        val rec =
            ArtifactRecord(
                artifactId = UUID.randomUUID(),
                userId = artifact.userId,
                tenantId = artifact.tenantId,
                kind = artifact.kind,
                name = artifact.name,
                agentId = artifact.agentId,
                envelopeJson = artifact.envelopeJson,
                provenanceJson = artifact.provenanceJson,
                appliedContextJson = artifact.appliedContextJson,
                displayStateJson = artifact.displayStateJson,
                paramsJson = artifact.paramsJson,
                refreshMode = artifact.refreshMode,
                paramMode = artifact.paramMode,
                templateId = artifact.templateId,
                memberIds = artifact.memberIds,
                layoutJson = artifact.layoutJson,
                createdAt = now,
                updatedAt = now,
            )
        rows[rec.artifactId] = rec
        return rec
    }

    override fun get(artifactId: UUID): ArtifactRecord? = rows[artifactId]

    override fun list(
        userId: String,
        kind: ArtifactKind?,
    ): List<ArtifactRecord> =
        rows.values
            .filter { it.userId == userId && (kind == null || it.kind == kind) }
            .sortedByDescending { it.updatedAt }

    override fun patch(
        artifactId: UUID,
        patch: ArtifactPatch,
    ): ArtifactRecord? {
        val existing = rows[artifactId] ?: return null
        val updated =
            existing.copy(
                name = patch.name ?: existing.name,
                paramsJson = patch.paramsJson ?: existing.paramsJson,
                layoutJson = patch.layoutJson ?: existing.layoutJson,
                memberIds = patch.memberIds ?: existing.memberIds,
                refreshMode = patch.refreshMode ?: existing.refreshMode,
                updatedAt = Instant.now(),
            )
        rows[artifactId] = updated
        return updated
    }

    override fun delete(artifactId: UUID): Boolean = rows.remove(artifactId) != null

    override fun recordRefresh(
        artifactId: UUID,
        refreshedAt: Instant,
        envelopeJson: String?,
        refreshError: String?,
    ): ArtifactRecord? {
        val existing = rows[artifactId] ?: return null
        val updated =
            existing.copy(
                envelopeJson = envelopeJson ?: existing.envelopeJson,
                refreshedAt = refreshedAt,
                refreshError = refreshError,
                updatedAt = Instant.now(),
            )
        rows[artifactId] = updated
        return updated
    }
}
