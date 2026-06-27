@file:Suppress("MagicNumber", "MaxLineLength", "NewLineAtEndOfFile", "TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.api.LlmProvider
import org.tatrman.kantheon.hebe.api.StreamEvent
import org.tatrman.kantheon.hebe.config.HebeConfig
import org.tatrman.kantheon.hebe.memory.db.Db
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory

class DailyDigest(
    private val db: Db,
    private val llmProvider: LlmProvider,
    private val workspaceRoot: String = "/tmp/hebe",
    private val receiptsDir: String = "/tmp/hebe/receipts",
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ds: DataSource get() = db.dataSource

    companion object {
        const val DEFAULT_CRON = "5 0 * * *"
        const val DAILY_SUBDIR = "daily"
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val workspaceRoot: String,
        val receiptsDir: String,
    ) {
        companion object {
            fun fromConfig(cfg: HebeConfig): Config {
                val base = cfg.hebe.dataDir.replace("~", System.getProperty("user.home"))
                return Config(workspaceRoot = base, receiptsDir = "$base/receipts")
            }
        }
    }

    @Suppress("NestedBlockDepth")
    suspend fun run(nowMs: Long = System.currentTimeMillis()): Result<Boolean> {
        return try {
            val yesterdayStart = startOfYesterday(nowMs)
            val yesterdayEnd = endOfYesterday(nowMs)

            val messageCount = countMessagesInRange(yesterdayStart, yesterdayEnd)
            val jobCount = countNonHeartbeatJobsInRange(yesterdayStart, yesterdayEnd)

            if (messageCount == 0 && jobCount == 0) {
                logger.info("daily digest: no activity yesterday, skipping")
                return Result.success(false)
            }

            val messages = loadMessagesInRange(yesterdayStart, yesterdayEnd)
            val receipts = loadReceiptsInRange(yesterdayStart, yesterdayEnd)
            val jobs = loadJobsInRange(yesterdayStart, yesterdayEnd)

            val dateStr = dateFromTimestamp(yesterdayStart)
            val digest = generateDigest(dateStr, messages, receipts, jobs)
            if (digest.isBlank()) {
                logger.info("daily digest: LLM returned empty digest, skipping file write")
                return Result.success(false)
            }

            val outputPath = Path.of(workspaceRoot, DAILY_SUBDIR, "$dateStr.md")
            Files.createDirectories(outputPath.parent)
            Files.writeString(outputPath, digest)
            logger.info("daily digest written to {}", outputPath)
            Result.success(true)
        } catch (e: Exception) {
            logger.error("daily digest failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    private fun startOfYesterday(nowMs: Long): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs)
        val yesterday = now.minus(java.time.Duration.ofDays(1))
        val start = yesterday.atZone(java.time.ZoneOffset.UTC).toLocalDate().atStartOfDay()
        return start.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }

    private fun endOfYesterday(nowMs: Long): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs)
        val yesterday = now.minus(java.time.Duration.ofDays(1))
        val end = yesterday.atZone(java.time.ZoneOffset.UTC).toLocalDate().atTime(java.time.LocalTime.MAX)
        return end.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }

    private fun dateFromTimestamp(ts: Long): String =
        java.time.Instant
            .ofEpochMilli(ts)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    private fun countMessagesInRange(
        start: Long,
        end: Long,
    ): Int {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE ts >= ? AND ts < ?",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(start))
                    ps.setTimestamp(2, Timestamp(end))
                    val rs = ps.executeQuery()
                    rs.next()
                    return rs.getInt(1)
                }
        }
    }

    private fun countNonHeartbeatJobsInRange(
        start: Long,
        end: Long,
    ): Int {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT COUNT(*) FROM jobs WHERE started_at >= ? AND started_at < ? AND kind != 'heartbeat'",
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(start))
                    ps.setTimestamp(2, Timestamp(end))
                    val rs = ps.executeQuery()
                    rs.next()
                    return rs.getInt(1)
                }
        }
    }

    private fun loadMessagesInRange(
        start: Long,
        end: Long,
    ): List<MessageRow> {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT m.id, m.conversation_id, m.role, m.content, m.ts
                    FROM messages m
                    WHERE m.ts >= ? AND m.ts < ?
                    ORDER BY m.ts ASC
                    LIMIT 500
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(start))
                    ps.setTimestamp(2, Timestamp(end))
                    val rs = ps.executeQuery()
                    val rows = mutableListOf<MessageRow>()
                    while (rs.next()) {
                        rows.add(
                            MessageRow(
                                id = rs.getString(1),
                                convId = rs.getString(2),
                                role = rs.getString(3),
                                content = rs.getString(4),
                                ts = rs.getTimestamp(5).time,
                            ),
                        )
                    }
                    return rows
                }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun loadReceiptsInRange(
        start: Long,
        end: Long,
    ): List<String> {
        val receiptsPath = Path.of(receiptsDir)
        if (!Files.exists(receiptsPath)) return emptyList()
        return try {
            val result = mutableListOf<String>()
            Files.list(receiptsPath).forEach { path ->
                if (path.fileName.toString().endsWith(".ndjson")) {
                    try {
                        Files.readAllLines(path).forEach { line ->
                            val tsMatch = Regex(""""ts"\s*:\s*(\d+)""").find(line)
                            val ts = tsMatch?.groupValues?.get(1)?.toLongOrNull()
                            if (ts != null && ts in start..end) {
                                result.add(line)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            result.take(200)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadJobsInRange(
        start: Long,
        end: Long,
    ): List<JobRow> {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, kind, status, result_json FROM jobs
                    WHERE started_at >= ? AND started_at < ?
                    ORDER BY started_at ASC
                    LIMIT 100
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(start))
                    ps.setTimestamp(2, Timestamp(end))
                    val rs = ps.executeQuery()
                    val rows = mutableListOf<JobRow>()
                    while (rs.next()) {
                        rows.add(
                            JobRow(
                                id = rs.getString(1),
                                kind = rs.getString(2),
                                status = rs.getString(3),
                                resultJson = rs.getString(4),
                            ),
                        )
                    }
                    return rows
                }
        }
    }

    private suspend fun generateDigest(
        dateStr: String,
        messages: List<MessageRow>,
        receipts: List<String>,
        jobs: List<JobRow>,
    ): String {
        val prompt = buildDigestPrompt(dateStr, messages, receipts, jobs)
        val model = llmProvider.capabilities().defaultModel.ifEmpty { "default" }
        val request =
            org.tatrman.kantheon.hebe.api.ChatRequest(
                model = model,
                systemPrompt = "You are a daily digest generator. Output ONLY the markdown document with sections: Conversations, Tools called, Facts learned, Issues encountered.",
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
                is StreamEvent.TextDelta -> textParts.add(event.text)
                else -> {}
            }
        }
        return textParts.joinToString("")
    }

    private fun buildDigestPrompt(
        dateStr: String,
        messages: List<MessageRow>,
        receipts: List<String>,
        jobs: List<JobRow>,
    ): String {
        val sb = StringBuilder("# Daily Digest $dateStr\n\n")

        sb.append("## Conversations\n")
        val byConv = messages.groupBy { it.convId }
        for ((convId, msgs) in byConv.entries.take(10)) {
            sb.append("### $convId\n")
            for (msg in msgs.take(5)) {
                sb.append("[${msg.role}] ${msg.content.take(200)}\n")
            }
            if (msgs.size > 5) sb.append("_(${msgs.size - 5} more messages)_\n")
            sb.append("\n")
        }

        sb.append("## Tools called\n")
        for (receipt in receipts.take(20)) {
            val toolMatch = Regex(""""tool"\s*:\s*"([^"]+)"""").find(receipt)
            if (toolMatch != null) {
                sb.append("- ${toolMatch.groupValues[1]}\n")
            }
        }
        sb.append("\n")

        sb.append("## Jobs\n")
        for (job in jobs.take(20)) {
            sb.append("- [${job.kind}] ${job.status}\n")
        }
        sb.append("\n")

        sb.append("## Issues encountered\n")
        val failedJobs = jobs.filter { it.status == "failed" }
        for (job in failedJobs.take(5)) {
            sb.append("- Job ${job.id} (${job.kind}) failed\n")
        }
        if (failedJobs.isEmpty()) sb.append("- No issues encountered.\n")

        return sb.toString()
    }

    data class MessageRow(
        val id: String,
        val convId: String,
        val role: String,
        val content: String,
        val ts: Long,
    )

    data class JobRow(
        val id: String,
        val kind: String,
        val status: String,
        val resultJson: String?,
    )
}
