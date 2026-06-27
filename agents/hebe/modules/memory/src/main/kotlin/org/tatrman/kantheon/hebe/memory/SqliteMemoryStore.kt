@file:Suppress("detekt:MagicNumber", "detekt:UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.memory

import org.tatrman.kantheon.hebe.api.ChatRole
import org.tatrman.kantheon.hebe.api.ConversationMessage
import org.tatrman.kantheon.hebe.api.HebeException
import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryHit
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.MemorySnapshot
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.api.Observer
import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.memory.embeddings.EmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneResult
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.indexer.Indexer
import org.tatrman.kantheon.hebe.memory.search.Searcher
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import org.tatrman.kantheon.hebe.memory.workspace.WorkspacePath
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SqliteMemoryStore(
    private val db: Db,
    private val workspaceFs: WorkspaceFs,
    private val embeddings: EmbeddingProvider,
    private val hygieneScanner: HygieneScanner,
    observer: Observer?,
) : MemoryStore {
    companion object {
        private const val PARAM_CONV_ID = 1
        private const val PARAM_CHANNEL = 2
        private const val PARAM_USER_ID = 3
        private const val PARAM_STARTED_AT = 4
        private const val PARAM_MSG_ID = 5
        private const val PARAM_ROLE = 6
        private const val PARAM_CONTENT = 7
        private const val PARAM_TOOL_CALLS = 8
        private const val PARAM_TS = 9
    }

    private val indexer = Indexer(db, embeddings)
    private val searcher = Searcher(db, embeddings, observer)
    private val systemPromptAssembler = SystemPromptAssembler(workspaceFs)
    private val mutex = Mutex()

    override suspend fun appendMessage(
        conversationId: String,
        msg: ConversationMessage,
    ) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                db.dataSource.connection.use { conn ->
                    conn
                        .prepareStatement(
                            """
                            INSERT OR IGNORE INTO conversations(id, channel, user_id, started_at)
                            VALUES (?, 'cli', 'operator', ?)
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(PARAM_CONV_ID, conversationId)
                            ps.setLong(PARAM_CHANNEL, msg.ts.toEpochMilliseconds())
                            ps.executeUpdate()
                        }
                    conn
                        .prepareStatement(
                            """
                            INSERT INTO messages(conversation_id, id, role, content, tool_calls, ts)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(PARAM_CONV_ID, conversationId)
                            ps.setString(PARAM_MSG_ID, msg.id.toString())
                            ps.setString(PARAM_ROLE, msg.role.name)
                            ps.setString(PARAM_CONTENT, msg.content)
                            ps.setString(PARAM_TOOL_CALLS, "[]")
                            ps.setLong(PARAM_TS, msg.ts.toEpochMilliseconds())
                            ps.executeUpdate()
                        }
                }
            }
        }
    }

    override suspend fun loadContext(
        conversationId: String,
        limit: Int,
    ): List<ConversationMessage> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val msgs = mutableListOf<ConversationMessage>()
                db.dataSource.connection.use { conn ->
                    conn
                        .prepareStatement(
                            """
                            SELECT id, role, content, ts FROM messages
                            WHERE conversation_id = ?
                            ORDER BY ts DESC LIMIT ?
                            """.trimIndent(),
                        ).use { ps ->
                            ps.setString(PARAM_CONV_ID, conversationId)
                            ps.setInt(2, limit)
                            val rs = ps.executeQuery()
                            while (rs.next()) {
                                msgs.add(
                                    ConversationMessage(
                                        id = UUID.fromString(rs.getString(1)),
                                        role = ChatRole.valueOf(rs.getString(2)),
                                        content = rs.getString(3),
                                        toolCalls = emptyList(),
                                        ts = Instant.fromEpochMilliseconds(rs.getLong(4)),
                                    ),
                                )
                            }
                        }
                }
                msgs.reversed()
            }
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
        val verdict = hygieneScanner.scan(content)
        when (verdict) {
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
        val wp = if (prefix.isEmpty()) WorkspacePath("") else WorkspacePath(prefix)
        return workspaceFs.list(wp).map { it.value }
    }

    override suspend fun systemPrompt(isGroup: Boolean): String = systemPromptAssembler.assemble(isGroup)

    override suspend fun snapshot(): MemorySnapshot =
        withContext(Dispatchers.IO) {
            db.dataSource.connection.use { conn ->
                val conversations =
                    conn.createStatement().use { st ->
                        val rs = st.executeQuery("SELECT COUNT(*) FROM conversations")
                        rs.next()
                        rs.getInt(1)
                    }
                val docs =
                    conn.createStatement().use { st ->
                        val rs = st.executeQuery("SELECT COUNT(*) FROM memory_docs")
                        rs.next()
                        rs.getInt(1)
                    }
                val chunks =
                    conn.createStatement().use { st ->
                        val rs = st.executeQuery("SELECT COUNT(*) FROM memory_chunks")
                        rs.next()
                        rs.getInt(1)
                    }
                MemorySnapshot(conversations, docs, chunks)
            }
        }
}
