package org.tatrman.kantheon.hebe.api

import kotlin.time.Instant
import kotlinx.serialization.Serializable

interface MemoryStore {
    suspend fun appendMessage(
        conversationId: String,
        msg: ConversationMessage,
    )

    suspend fun loadContext(
        conversationId: String,
        limit: Int = 64,
    ): List<ConversationMessage>

    suspend fun search(
        query: String,
        k: Int = 10,
        scope: MemoryScope = MemoryScope.Default,
        categories: Set<MemoryCategory>? = null,
    ): List<MemoryHit>

    suspend fun appendDoc(
        path: String,
        content: String,
        scope: MemoryScope = MemoryScope.Default,
        category: MemoryCategory = MemoryCategory.Document,
    )

    suspend fun readDoc(path: String): String?

    suspend fun listDocs(prefix: String): List<String>

    suspend fun systemPrompt(isGroup: Boolean = false): String

    suspend fun snapshot(): MemorySnapshot
}

@Serializable
data class ConversationMessage(
    @Serializable(with = UUIDSerializer::class) val id: java.util.UUID,
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ParsedToolCall>,
    val ts: Instant,
)

@Serializable
enum class ChatRole {
    User,
    Assistant,
    System,
    Tool,
}

@Serializable
data class MemoryHit(
    val docPath: String,
    val chunkIdx: Int,
    val snippet: String,
    val score: Double,
    val source: HitSource,
)

@Serializable
enum class HitSource {
    Fts,
    Vector,
    Both,
}

@Serializable
enum class MemoryScope {
    Default,
    Identity,
    Daily,
}

@Serializable
enum class MemoryCategory {
    Conversation,
    Fact,
    Preference,
    Skill,
    Document,
}

data class MemorySnapshot(
    val conversations: Int,
    val docs: Int,
    val chunks: Int,
)
