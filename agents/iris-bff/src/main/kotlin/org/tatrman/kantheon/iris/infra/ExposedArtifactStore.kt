@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.iris.infra

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.iris.domain.ArtifactKind
import org.tatrman.kantheon.iris.domain.ArtifactPatch
import org.tatrman.kantheon.iris.domain.ArtifactRecord
import org.tatrman.kantheon.iris.domain.ArtifactStore
import org.tatrman.kantheon.iris.domain.NewArtifact
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Postgres-backed [ArtifactStore] (Exposed, PD-6 / contracts §3.3). Real-PG
 * fidelity is integration-deferred (planning-conventions §4) — the in-memory
 * fake backs the unit/component gate. JSON columns are stored as raw JSON
 * strings (the route layer owns proto/DTO shaping).
 */
class ExposedArtifactStore(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
    private val ids: () -> UUID = UUID::randomUUID,
) : ArtifactStore {
    private fun UUID.k(): Uuid = toKotlinUuid()

    private fun Uuid.j(): UUID = toJavaUuid()

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    override fun create(artifact: NewArtifact): ArtifactRecord {
        val id = ids()
        val now = clock()
        db.query {
            IrisArtifacts.insert {
                it[artifactId] = id.k()
                it[userId] = artifact.userId
                it[tenantId] = artifact.tenantId
                it[kind] = artifact.kind.wire
                it[name] = artifact.name
                it[agentId] = artifact.agentId
                it[envelopeJson] = artifact.envelopeJson
                it[provenance] = artifact.provenanceJson
                it[appliedContext] = artifact.appliedContextJson
                it[displayState] = artifact.displayStateJson
                it[paramsJson] = artifact.paramsJson
                it[refreshMode] = artifact.refreshMode
                it[paramMode] = artifact.paramMode
                it[templateId] = artifact.templateId
                it[memberIds] = artifact.memberIds.map { m -> m.k() }
                it[layoutJson] = artifact.layoutJson
                it[createdAt] = now.odt()
                it[updatedAt] = now.odt()
            }
        }
        return get(id) ?: error("artifact $id vanished after insert")
    }

    override fun get(artifactId: UUID): ArtifactRecord? =
        db.query {
            IrisArtifacts
                .selectAll()
                .where { IrisArtifacts.artifactId eq artifactId.k() }
                .singleOrNull()
                ?.toRecord()
        }

    override fun list(
        userId: String,
        kind: ArtifactKind?,
    ): List<ArtifactRecord> =
        db.query {
            IrisArtifacts
                .selectAll()
                .where { IrisArtifacts.userId eq userId }
                .map { it.toRecord() }
                .filter { kind == null || it.kind == kind }
                .sortedByDescending { it.updatedAt }
        }

    override fun patch(
        artifactId: UUID,
        patch: ArtifactPatch,
    ): ArtifactRecord? {
        db.query {
            IrisArtifacts.update({ IrisArtifacts.artifactId eq artifactId.k() }) {
                patch.name?.let { v -> it[name] = v }
                patch.paramsJson?.let { v -> it[paramsJson] = v }
                patch.layoutJson?.let { v -> it[layoutJson] = v }
                patch.memberIds?.let { v -> it[memberIds] = v.map { m -> m.k() } }
                patch.refreshMode?.let { v -> it[refreshMode] = v }
                it[updatedAt] = clock().odt()
            }
        }
        return get(artifactId)
    }

    override fun delete(artifactId: UUID): Boolean =
        db.query {
            IrisArtifacts.deleteWhere { IrisArtifacts.artifactId eq artifactId.k() } > 0
        }

    override fun recordRefresh(
        artifactId: UUID,
        refreshedAt: Instant,
        envelopeJson: String?,
        refreshError: String?,
    ): ArtifactRecord? {
        db.query {
            IrisArtifacts.update({ IrisArtifacts.artifactId eq artifactId.k() }) {
                if (envelopeJson != null) it[IrisArtifacts.envelopeJson] = envelopeJson
                it[IrisArtifacts.refreshedAt] = refreshedAt.odt()
                it[IrisArtifacts.refreshError] = refreshError
                it[updatedAt] = clock().odt()
            }
        }
        return get(artifactId)
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord(): ArtifactRecord =
        ArtifactRecord(
            artifactId = this[IrisArtifacts.artifactId].j(),
            userId = this[IrisArtifacts.userId],
            tenantId = this[IrisArtifacts.tenantId],
            kind = ArtifactKind.fromWire(this[IrisArtifacts.kind]),
            name = this[IrisArtifacts.name],
            agentId = this[IrisArtifacts.agentId],
            envelopeJson = this[IrisArtifacts.envelopeJson],
            provenanceJson = this[IrisArtifacts.provenance],
            appliedContextJson = this[IrisArtifacts.appliedContext],
            displayStateJson = this[IrisArtifacts.displayState],
            paramsJson = this[IrisArtifacts.paramsJson],
            refreshMode = this[IrisArtifacts.refreshMode],
            paramMode = this[IrisArtifacts.paramMode],
            templateId = this[IrisArtifacts.templateId],
            memberIds = this[IrisArtifacts.memberIds]?.map { it.j() } ?: emptyList(),
            layoutJson = this[IrisArtifacts.layoutJson],
            refreshedAt = this[IrisArtifacts.refreshedAt]?.toInstant(),
            refreshError = this[IrisArtifacts.refreshError],
            createdAt = this[IrisArtifacts.createdAt].toInstant(),
            updatedAt = this[IrisArtifacts.updatedAt].toInstant(),
        )
}
