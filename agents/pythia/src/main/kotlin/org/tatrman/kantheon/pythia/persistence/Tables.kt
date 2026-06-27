@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.pythia.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed mappings for the Pythia schema (`V1__pythia_core.sql`). JSONB columns
 * are carried as raw proto3-JSON strings (identity codec — Pythia treats the
 * payloads opaquely at persistence; golem idiom). Exposed 1.0 `uuid()` columns
 * are `kotlin.uuid.Uuid`; the repositories convert to/from `java.util.UUID`.
 */
internal object PythiaInvestigationsTable : Table("pythia_investigations") {
    val id = uuid("id")
    val parentId = uuid("parent_id").nullable()
    val caller = jsonb("caller", { it }, { it })
    val question = text("question")
    val request = jsonb("request", { it }, { it })
    val status = text("status")
    val resolution = jsonb("resolution", { it }, { it }).nullable()
    val plan = jsonb("plan", { it }, { it }).nullable()
    val conclusion = jsonb("conclusion", { it }, { it }).nullable()
    val resourceUsage = jsonb("resource_usage", { it }, { it })
    val warnings = jsonb("warnings", { it }, { it })
    val awaitingSince = timestampWithTimeZone("awaiting_since").nullable()
    val awaitingTtlUntil = timestampWithTimeZone("awaiting_ttl_until").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val finalisedAt = timestampWithTimeZone("finalised_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object PythiaHypothesesTable : Table("pythia_hypotheses") {
    val investigationId = uuid("investigation_id")
    val hypId = text("hyp_id")
    val parentHypId = text("parent_hyp_id").nullable()
    val body = jsonb("body", { it }, { it })
    val status = text("status")
    val confidence = double("confidence").nullable()
    override val primaryKey = PrimaryKey(investigationId, hypId)
}

internal object PythiaStepsTable : Table("pythia_steps") {
    val investigationId = uuid("investigation_id")
    val stepId = text("step_id")
    val nodeId = text("node_id")
    val body = jsonb("body", { it }, { it })
    val status = text("status")
    val outputHandle = jsonb("output_handle", { it }, { it }).nullable()
    override val primaryKey = PrimaryKey(investigationId, stepId)
}

internal object PythiaHandlesTable : Table("pythia_handles") {
    val investigationId = uuid("investigation_id")
    val handleId = text("handle_id")
    val kind = text("kind")
    val body = jsonb("body", { it }, { it })
    val inlineData = binary("inline_data").nullable()
    override val primaryKey = PrimaryKey(investigationId, handleId)
}

internal object PythiaCheckpointsTable : Table("pythia_checkpoints") {
    val investigationId = uuid("investigation_id")
    val seq = integer("seq")
    val takenAt = timestampWithTimeZone("taken_at")
    val reason = text("reason")
    val schedulerState = jsonb("scheduler_state", { it }, { it })
    val diff = jsonb("diff", { it }, { it })
    override val primaryKey = PrimaryKey(investigationId, seq)
}

internal object PythiaEventsTable : Table("pythia_events") {
    val investigationId = uuid("investigation_id")
    val sequence = long("sequence")
    val emittedAt = timestampWithTimeZone("emitted_at")
    val kind = text("kind")
    val payload = jsonb("payload", { it }, { it })
    override val primaryKey = PrimaryKey(investigationId, sequence)
}
