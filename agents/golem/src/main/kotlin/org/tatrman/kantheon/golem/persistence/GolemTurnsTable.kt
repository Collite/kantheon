@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.golem.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed mapping for `golem_turns` (Flyway-managed schema, `V1__golem_core.sql`).
 * JSONB columns are carried as raw JSON strings (identity serialize/deserialize —
 * Golem treats the proto3-JSON payloads opaquely at persistence). Exposed 1.0
 * `uuid()` columns are `kotlin.uuid.Uuid`; [ExposedTurnsRepository] converts
 * to/from `java.util.UUID` at the boundary.
 */
internal object GolemTurnsTable : Table("golem_turns") {
    val id = uuid("id")
    val requestId = uuid("request_id")
    val golemId = text("golem_id")
    val userId = text("user_id")
    val tenantId = text("tenant_id")
    val question = text("question")
    val resolvedIntent = jsonb("resolved_intent", { it }, { it })
    val plan = jsonb("plan", { it }, { it })
    val envelopes = jsonb("envelopes", { it }, { it })
    val currentView = jsonb("current_view", { it }, { it }).nullable()
    val stepRecords = jsonb("step_records", { it }, { it })
    val resourceUsage = jsonb("resource_usage", { it }, { it })
    val pendingResumeToken = text("pending_resume_token").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val finalisedAt = timestampWithTimeZone("finalised_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("golem_turns_request", false, requestId)
    }
}
