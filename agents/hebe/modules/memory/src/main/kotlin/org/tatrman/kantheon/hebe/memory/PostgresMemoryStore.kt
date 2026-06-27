package org.tatrman.kantheon.hebe.memory

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.HebeException
import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.MemorySnapshot
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.memory.db.PgConversations
import org.tatrman.kantheon.hebe.memory.db.PgDb
import org.tatrman.kantheon.hebe.memory.db.PgMemoryChunks
import org.tatrman.kantheon.hebe.memory.db.PgMemoryDocs
import org.tatrman.kantheon.hebe.memory.db.PgMessages
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneResult
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.indexer.PgIndexer
import org.tatrman.kantheon.hebe.memory.search.PgSearcher
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Postgres [MemoryStore] (`storage.backend = postgres`: `server` + `k8s`),
 * mirroring [SqliteMemoryStore]'s public contract exactly. Relational CRUD
 * (conversations / messages / snapshot counts) goes through Exposed DSL
 * ([PgConversations]/[PgMessages]/…); doc indexing + hybrid retrieval delegate to
 * [PgIndexer]/[PgSearcher] (the search projections, raw SQL). The workspace surface
 * (`readDoc`/`listDocs`/`systemPrompt`) still rides [WorkspaceFs] — on `k8s` that is
 * swapped for the PG workspace backend at Stage 3.2, not here.
 */
class PostgresMemoryStore(
    private val db: PgDb,
    private val workspaceFs: WorkspaceFs,
    embeddings: EmbeddingProvider,
    private val hygieneScanner: HygieneScanner,
    observer: Observer?,
) : MemoryStore {
    private val indexer = PgIndexer(db, embeddings)
    private val searcher = PgSearcher(db, embeddings, observer)
    private val systemPromptAssembler = SystemPromptAssembler(workspaceFs)

    override suspend fun appendMessage(
        conversationId: String,
        msg: ConversationMessage,
    ) {
        val at = msg.ts.toOffsetDateTime()
        // db.query wraps a blocking Exposed transaction{}; offload to Dispatchers.IO so it
        // never blocks the calling (agent/scheduler) dispatcher — consistent with the
        // SQLite store, PgIndexer, PgSearcher and PostgresReceiptsStore.
        withContext(Dispatchers.IO) {
            db.query {
                PgConversations.insertIgnore {
                    it[id] = conversationId
                    it[channel] = "cli"
                    it[userId] = "operator"
                    it[startedAt] = at
                }
                PgMessages.insert {
                    it[id] = msg.id.toString()
                    it[this.conversationId] = conversationId
                    it[role] = msg.role.name
                    it[content] = msg.content
                    it[ts] = at
                }
            }
        }
    }

    override suspend fun loadContext(
        conversationId: String,
        limit: Int,
    ): List<ConversationMessage> =
        withContext(Dispatchers.IO) {
            db
                .query {
                    PgMessages
                        .selectAll()
                        .where { PgMessages.conversationId eq conversationId }
                        .orderBy(PgMessages.ts to SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            ConversationMessage(
                                id = UUID.fromString(row[PgMessages.id]),
                                role = ChatRole.valueOf(row[PgMessages.role]),
                                content = row[PgMessages.content],
                                toolCalls = emptyList(),
                                ts = row[PgMessages.ts].toKotlinInstant(),
                            )
                        }
                }.reversed()
        }

    override suspend fun search(
        query: String,
        k: Int,
        scope: MemoryScope,
        categories: Set<MemoryCategory>?,
    ): List<MemoryHit> = searcher.search(query, k, scope, categories)

    override suspend fun appendDoc(
        path: String,
        content: String,
        scope: MemoryScope,
        category: MemoryCategory,
    ) {
        when (val verdict = hygieneScanner.scan(content)) {
            is HygieneResult.Reject -> throw HebeException.Memory("rejected: ${verdict.findings.first().rule}")
            is HygieneResult.Warn -> {}
            is HygieneResult.Clean -> {}
        }
        val wp = WorkspacePath(path)
        indexer.indexDoc(wp, content, scope, category)
        workspaceFs.write(wp, content)
    }

    override suspend fun readDoc(path: String): String? = workspaceFs.read(WorkspacePath(path))

    override suspend fun listDocs(prefix: String): List<String> {
        val wp = WorkspacePath(prefix)
        return workspaceFs.list(wp).map { it.value }
    }

    override suspend fun systemPrompt(isGroup: Boolean): String = systemPromptAssembler.assemble(isGroup)

    override suspend fun snapshot(): MemorySnapshot =
        withContext(Dispatchers.IO) {
            db.query {
                MemorySnapshot(
                    conversations = PgConversations.selectAll().count().toInt(),
                    docs = PgMemoryDocs.selectAll().count().toInt(),
                    chunks = PgMemoryChunks.selectAll().count().toInt(),
                )
            }
        }

    private fun Instant.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(toEpochMilliseconds()), ZoneOffset.UTC)

    private fun OffsetDateTime.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(toInstant().toEpochMilli())
}
