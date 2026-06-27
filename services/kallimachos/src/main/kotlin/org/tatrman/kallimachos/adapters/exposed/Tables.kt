package org.tatrman.kallimachos.adapters.exposed

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Exposed table mappings for the `kallimachos` corpus (Flyway-managed schema,
 * `V1`/`V2`). JSONB columns are carried as raw JSON strings (the store treats
 * them opaquely; the model serialises). `parts.content_tsv` is DB-maintained
 * (the V2 trigger) and not mapped — the full-text search rides raw SQL.
 *
 * The `id` columns default from the shared `corpus_node_id` sequence
 * (`PostgresIds.nextNodeId`); they are not Exposed-autoinc.
 */
internal object Notebooks : Table("notebooks") {
    val id = text("id")
    val displayName = text("display_name")
    val ownerUserId = text("owner_user_id")
    val visibilityRoles = array<String>("visibility_roles")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object Sources : Table("sources") {
    val id = long("id")
    val assetRef = text("asset_ref")
    val mimeType = text("mime_type")
    val title = text("title")
    val metadata = jsonb("metadata", { it }, { it })
    val embeddingStatus = text("embedding_status")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object Parts : Table("parts") {
    val id = long("id")
    val sourceId = long("source_id").references(Sources.id)
    val idx = integer("idx")
    val kind = text("kind")
    val contentText = text("content_text")
    val metadata = jsonb("metadata", { it }, { it })
    override val primaryKey = PrimaryKey(id)
}

internal object Pages : Table("pages") {
    val id = long("id")
    val kind = text("kind")
    val title = text("title")
    val contentMd = text("content_md")
    val conceptRef = jsonb("concept_ref", { it }, { it }).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object NotebookMembers : Table("notebook_members") {
    val notebookId = text("notebook_id").references(Notebooks.id)
    val nodeKind = text("node_kind")
    val nodeId = long("node_id")
    override val primaryKey = PrimaryKey(notebookId, nodeKind, nodeId)
}

// GRAPH plane (V4) — the adjacency-table fallback behind GraphPort (spike verdict).
internal object GraphNodes : Table("graph_nodes") {
    val kind = text("kind")
    val id = long("id")
    override val primaryKey = PrimaryKey(kind, id)
}

internal object GraphEdges : Table("graph_edges") {
    val fromKind = text("from_kind")
    val fromId = long("from_id")
    val toKind = text("to_kind")
    val toId = long("to_id")
    val kind = text("kind")
    val weight = double("weight")
    override val primaryKey = PrimaryKey(fromKind, fromId, toKind, toId, kind)
}
