package org.tatrman.kantheon.hebe.memory.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * Exposed table mappings for the Postgres memory backend (Flyway-managed schema,
 * `db/migration-pg/`). Per the kantheon convention (architecture §5.1), the
 * relational CRUD (conversations, messages, snapshot counts) goes through these
 * Exposed objects — never raw SQL. The two search **projections** (`memory_chunks`
 * full-text + vector candidate reads, and the embedding write) stay raw SQL because
 * `tsvector`/`ts_rank_cd`/pgvector `<=>`/`::vector` are not expressible in the DSL —
 * the same split the SQLite backend draws around its FTS5/vec virtual tables. The
 * `tsv` (generated) and `embedding` (`vector`) columns are therefore deliberately
 * unmapped here.
 */
internal object PgConversations : Table("conversations") {
    val id = text("id")
    val channel = text("channel")
    val userId = text("user_id")
    val externalId = text("external_id").nullable()
    val startedAt = timestampWithTimeZone("started_at")
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object PgMessages : Table("messages") {
    val id = text("id")
    val conversationId = text("conversation_id").references(PgConversations.id)
    val role = text("role")
    val content = text("content")
    val toolCallId = text("tool_call_id").nullable()
    val ts = timestampWithTimeZone("ts")
    val summaryId = text("summary_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object PgMemoryDocs : Table("memory_docs") {
    val path = text("path")
    override val primaryKey = PrimaryKey(path)
}

internal object PgMemoryChunks : Table("memory_chunks") {
    val docPath = text("doc_path")
    val chunkIdx = integer("chunk_idx")
    override val primaryKey = PrimaryKey(docPath, chunkIdx)
}

internal object PgWorkspaceFiles : Table("workspace_files") {
    val path = text("path")
    val content = text("content")
    val revision = integer("revision")
    val updatedAt = timestampWithTimeZone("updated_at")
    val updatedBy = text("updated_by")
    override val primaryKey = PrimaryKey(path)
}
