@file:Suppress("MagicNumber", "MaxLineLength", "NewLineAtEndOfFile", "TooGenericExceptionCaught", "UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.db.Db
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.datetime.TimeZone
import org.slf4j.LoggerFactory

class Summariser(
    private val db: Db,
    private val llmProvider: LlmProvider,
    private val workspaceRoot: String = "/tmp/hebe",
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ds: DataSource get() = db.dataSource
    private val utc = TimeZone.UTC

    companion object {
        const val DEFAULT_CRON = "*/30 * * * *"
        const val TOKEN_THRESHOLD = 8000
        const val LOOKBACK_HOURS = 24
        const val MEMORY_MD = "MEMORY.md"
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val tokenThreshold: Int = TOKEN_THRESHOLD,
        val lookbackHours: Int = LOOKBACK_HOURS,
        val workspaceRoot: String,
    ) {
        companion object {
            fun fromConfig(cfg: HebeConfig): Config {
                val base = cfg.hebe.dataDir.replace("~", System.getProperty("user.home"))
                return Config(workspaceRoot = base)
            }
        }
    }

    @Suppress("NestedBlockDepth")
    suspend fun run(nowMs: Long = System.currentTimeMillis()): Result<Int> =
        try {
            val conversations = loadActiveConversations(nowMs)
            var summarised = 0
            for (conv in conversations) {
                try {
                    val count = summariseConversation(conv, nowMs)
                    if (count > 0) summarised += count
                } catch (e: Exception) {
                    logger.warn("summarisation failed for conv={}: {}", conv.id, e.message)
                }
            }
            Result.success(summarised)
        } catch (e: Exception) {
            logger.error("summariser run failed: {}", e.message, e)
            Result.failure(e)
        }

    private fun loadActiveConversations(nowMs: Long): List<ConversationRow> {
        val sinceMs = nowMs - LOOKBACK_HOURS * 3600 * 1000L
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT c.id, COUNT(m.id) as msg_count
                    FROM conversations c
                    JOIN messages m ON m.conversation_id = c.id
                    WHERE m.ts > ?
                    GROUP BY c.id
                    HAVING msg_count >= 3
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(sinceMs))
                    val rs = ps.executeQuery()
                    val rows = mutableListOf<ConversationRow>()
                    while (rs.next()) {
                        rows.add(ConversationRow(rs.getString(1), rs.getInt(2)))
                    }
                    return rows
                }
        }
    }

    private suspend fun summariseConversation(
        conv: ConversationRow,
        nowMs: Long,
    ): Int {
        val messages = loadMessagesForSummarisation(conv.id)
        if (messages.isEmpty()) return 0

        val totalTokens = estimateTokens(messages)
        if (totalTokens < TOKEN_THRESHOLD) return 0

        val unsummarised = messages
        if (unsummarised.size < 3) return 0

        val summary = generateSummary(unsummarised)
        if (summary.isBlank()) return 0

        val summaryId =
            java.util.UUID
                .randomUUID()
                .toString()
        val summarySection = buildSummarySection(summary, nowMs)

        appendToMemoryMd(conv.id, summarySection)

        markMessagesSummarised(unsummarised.map { it.id }, summaryId)

        return unsummarised.size
    }

    private fun loadMessagesForSummarisation(convId: String): List<MessageRow> {
        val sinceMs = System.currentTimeMillis() - LOOKBACK_HOURS * 3600 * 1000L
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, role, content, ts FROM messages
                    WHERE conversation_id = ? AND ts > ? AND summary_id IS NULL
                    ORDER BY ts ASC
                    LIMIT 200
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, convId)
                    ps.setTimestamp(2, Timestamp(sinceMs))
                    val rs = ps.executeQuery()
                    val messages = mutableListOf<MessageRow>()
                    while (rs.next()) {
                        messages.add(
                            MessageRow(
                                id = rs.getString(1),
                                role = rs.getString(2),
                                content = rs.getString(3),
                                ts = rs.getTimestamp(4).time,
                            ),
                        )
                    }
                    return messages
                }
        }
    }

    private suspend fun generateSummary(messages: List<MessageRow>): String {
        val prompt = buildSummarisationPrompt(messages)
        val model = llmProvider.capabilities().defaultModel.ifEmpty { "default" }
        val request =
            org.tatrman.kantheon.hebe.api.ChatRequest(
                model = model,
                systemPrompt = "You are a precise summariser. Summarise the conversation concisely, capturing key facts, decisions, and context. Output only the summary text.",
                messages =
                    listOf(
                        org.tatrman.kantheon.hebe.api.ChatMessage
                            .User(prompt),
                    ),
                tools = emptyList(),
                temperature = 0.3,
                stream = false,
            )
        val textParts = mutableListOf<String>()
        llmProvider.chat(request).collect { event ->
            when (event) {
                is org.tatrman.kantheon.hebe.api.StreamEvent.TextDelta -> textParts.add(event.text)
                else -> {}
            }
        }
        return textParts.joinToString("")
    }

    private fun buildSummarisationPrompt(messages: List<MessageRow>): String {
        val sb = StringBuilder("Summarise this conversation transcript:\n\n")
        for (msg in messages) {
            sb.append("[${msg.role}] ${msg.content}\n")
        }
        sb.append("\nProvide a concise summary capturing key points and context.")
        return sb.toString()
    }

    private fun buildSummarySection(
        summary: String,
        nowMs: Long,
    ): String {
        val ts = java.time.Instant.ofEpochMilli(nowMs)
        val dateStr = ts.toString().substringBefore("T")
        val timeStr = ts.toString().substringAfter("T").substringBefore(".")
        return "\n## Summary $dateStr $timeStr\n\n$summary\n"
    }

    private fun appendToMemoryMd(
        convId: String,
        section: String,
    ) {
        val path =
            java.nio.file.Path
                .of(workspaceRoot, convId, MEMORY_MD)
        val existing =
            if (java.nio.file.Files
                    .exists(path)
            ) {
                java.nio.file.Files
                    .readString(path)
            } else {
                ""
            }
        val updated = existing + section
        java.nio.file.Files
            .writeString(path, updated)
    }

    private fun markMessagesSummarised(
        messageIds: List<String>,
        summaryId: String,
    ) {
        if (messageIds.isEmpty()) return
        ds.connection.use { conn ->
            val placeholders = messageIds.indices.joinToString(",") { "?" }
            conn
                .prepareStatement(
                    "UPDATE messages SET summary_id = ? WHERE id IN ($placeholders)",
                ).use { ps ->
                    ps.setString(1, summaryId)
                    messageIds.forEachIndexed { idx, id -> ps.setString(idx + 2, id) }
                    ps.executeUpdate()
                }
        }
    }

    private fun estimateTokens(messages: List<MessageRow>): Int {
        val totalChars = messages.sumOf { it.content.length }
        return totalChars / 4
    }

    data class ConversationRow(
        val id: String,
        val msgCount: Int,
    )

    data class MessageRow(
        val id: String,
        val role: String,
        val content: String,
        val ts: Long,
        val summaryId: String? = null,
    )
}
