@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.iris.infra

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed table mappings for the iris-bff session tables (Flyway-managed schema,
 * `V1__iris_core.sql`). JSONB columns are carried as raw JSON strings
 * (identity serialize/deserialize — the BFF treats them opaquely). Exposed 1.0
 * `uuid()` columns are typed `kotlin.uuid.Uuid`; [ExposedSessionStore] converts
 * to/from `java.util.UUID` at the boundary. Only the tables the store touches at
 * Stage 1.2 are mapped; audit / feedback / artifact / v2-thread tables exist in
 * the migration and gain mappings as their write paths land (Stage 1.3 / Phase 4).
 */
internal object IrisSessions : Table("iris_sessions") {
    val sessionId = uuid("session_id")
    val userId = text("user_id")
    val tenantId = text("tenant_id")
    val entityContext = jsonb("entity_context", { it }, { it })
    val currentDisplay = jsonb("current_display", { it }, { it })
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(sessionId)
}

internal object IrisTurns : Table("iris_turns") {
    val turnId = uuid("turn_id")
    val sessionId = uuid("session_id").references(IrisSessions.sessionId)
    val seq = integer("seq")
    val agentId = text("agent_id")
    val artifactRef = text("artifact_ref").nullable()
    val question = text("question")
    val envelopeJson = jsonb("envelope_json", { it }, { it }).nullable()

    // matches the migration's `TEXT[] NOT NULL DEFAULT '{}'` (always set on insert,
    // but mirror the DDL default so the mapping is faithful).
    val displayedBlockIds = array<String>("displayed_block_ids").default(emptyList())

    // V2 migration: agents offered as RoutingPickChips on a needs_user_pick turn.
    val alternatesOffered = array<String>("alternates_offered").default(emptyList())
    val pendingResumeToken = text("pending_resume_token").nullable()
    val resumeIssuerAgentId = text("resume_issuer_agent_id").nullable()
    val status = text("status")
    val origin = text("origin")
    val originRef = text("origin_ref").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(turnId)

    init {
        uniqueIndex(sessionId, seq)
    }
}

internal object IrisV2Threads : Table("iris_v2_threads") {
    val sessionId = uuid("session_id").references(IrisSessions.sessionId)
    val v2ThreadId = text("v2_thread_id")
    override val primaryKey = PrimaryKey(sessionId)
}

internal object IrisAudit : Table("iris_audit") {
    // `seq` is DB-`GENERATED ALWAYS AS IDENTITY` — read after insert, never written.
    val seq = long("seq")
    val ts = timestampWithTimeZone("ts")
    val userId = text("user_id")
    val eventKind = text("event_kind")
    val payload = jsonb("payload", { it }, { it })
    val segment = text("segment")
    val prevHash = text("prev_hash")
    val selfHash = text("self_hash")
    val sig = text("sig")
    override val primaryKey = PrimaryKey(seq)
}

internal object IrisFeedback : Table("iris_feedback") {
    val feedbackId = uuid("feedback_id")
    val turnId = uuid("turn_id")
    val userId = text("user_id")
    val agentId = text("agent_id")
    val verdict = text("verdict")
    val reason = text("reason").nullable()
    val comment = text("comment").nullable()
    val correctedAgentId = text("corrected_agent_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(feedbackId)

    init {
        uniqueIndex(turnId, userId)
    }
}

internal object IrisArtifacts : Table("iris_artifacts") {
    val artifactId = uuid("artifact_id")
    val userId = text("user_id")
    val tenantId = text("tenant_id")
    val kind = text("kind")
    val name = text("name")
    val agentId = text("agent_id").nullable()
    val envelopeJson = jsonb("envelope_json", { it }, { it }).nullable()
    val provenance = jsonb("provenance", { it }, { it }).nullable()
    val appliedContext = jsonb("applied_context", { it }, { it }).nullable()
    val displayState = jsonb("display_state", { it }, { it }).nullable()
    val paramsJson = jsonb("params_json", { it }, { it }).nullable()
    val refreshMode = text("refresh_mode")
    val paramMode = text("param_mode").nullable()
    val templateId = text("template_id").nullable()
    val memberIds = array("member_ids", UuidColumnType()).nullable()
    val layoutJson = jsonb("layout_json", { it }, { it }).nullable()
    val refreshedAt = timestampWithTimeZone("refreshed_at").nullable()
    val refreshError = text("refresh_error").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(artifactId)
}

internal object IrisSnapshots : Table("iris_snapshots") {
    val snapshotId = uuid("snapshot_id")
    val sessionId = uuid("session_id").references(IrisSessions.sessionId)
    val reason = text("reason")
    val entityContext = jsonb("entity_context", { it }, { it })

    // Explicit element column type (vs reflective `array<Uuid>`): maps the
    // migration's `UUID[]` to a kotlin.uuid.Uuid array unambiguously.
    val turnIds = array("turn_ids", UuidColumnType())
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(snapshotId)
}
