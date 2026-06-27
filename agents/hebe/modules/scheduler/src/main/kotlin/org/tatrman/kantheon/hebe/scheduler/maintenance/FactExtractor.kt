@file:Suppress("MagicNumber", "MaxLineLength", "NewLineAtEndOfFile", "TooGenericExceptionCaught", "UnusedParameter")

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

class FactExtractor(
    private val db: Db,
    private val llmProvider: LlmProvider,
    private val workspaceRoot: String = "/tmp/hebe",
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ds: DataSource get() = db.dataSource

    companion object {
        const val DEFAULT_CRON = "10 * * * *"
        const val CONFIDENCE_THRESHOLD = 0.85
        const val DUPLICATE_THRESHOLD = 0.9
        const val LOOKBACK_HOURS = 1
        const val FACTS_FILE = "MEMORY.md"
        const val FACTS_SECTION = "## Facts"
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val confidenceThreshold: Double = CONFIDENCE_THRESHOLD,
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
            val facts = extractFacts(nowMs)
            var written = 0
            for (fact in facts) {
                try {
                    val appended = appendFact(fact, nowMs)
                    if (appended) written++
                } catch (e: Exception) {
                    logger.warn("failed to append fact: {}", e.message)
                }
            }
            Result.success(written)
        } catch (e: Exception) {
            logger.error("fact extractor run failed: {}", e.message, e)
            Result.failure(e)
        }

    private suspend fun extractFacts(nowMs: Long): List<ExtractedFact> {
        val sinceMs = nowMs - LOOKBACK_HOURS * 3600 * 1000L
        val messages = loadAssistantMessages(sinceMs)
        if (messages.isEmpty()) return emptyList()

        val prompt = buildFactExtractionPrompt(messages)
        val json = callLlmForFacts(prompt)

        return parseFactsFromJson(json)
    }

    private fun loadAssistantMessages(sinceMs: Long): List<MessageRow> {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, role, content, ts FROM messages
                    WHERE role = 'assistant' AND ts > ?
                    ORDER BY ts DESC
                    LIMIT 100
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(sinceMs))
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

    private fun buildFactExtractionPrompt(messages: List<MessageRow>): String {
        val sb =
            StringBuilder(
                """
                You are a fact extraction system. From the following assistant messages, extract durable facts about the user or their preferences.
                For each fact, provide a JSON object with: "fact" (the fact text), "turn_id" (the message id), "confidence" (0.0-1.0), "date" (ISO date).
                Return a JSON array of facts. Only include facts with confidence >= 0.85.
                If no facts found, return an empty array [].

                Messages:
                """.trimIndent(),
            )
        for (msg in messages) {
            sb.append("[${msg.id}] ${msg.content}\n")
        }
        return sb.toString()
    }

    private suspend fun callLlmForFacts(prompt: String): String {
        val model = llmProvider.capabilities().defaultModel.ifEmpty { "default" }
        val request =
            org.tatrman.kantheon.hebe.api.ChatRequest(
                model = model,
                systemPrompt = "You are a precise fact extractor. Return ONLY valid JSON array with no additional text.",
                messages =
                    listOf(
                        org.tatrman.kantheon.hebe.api.ChatMessage
                            .User(prompt),
                    ),
                tools = emptyList(),
                temperature = 0.1,
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

    private fun parseFactsFromJson(json: String): List<ExtractedFact> {
        if (json.isBlank()) return emptyList()
        return try {
            val elem =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(json)
            val arr = elem as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { item ->
                val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val fact = obj["fact"]?.toString()?.removeSurrounding("\"") ?: return@mapNotNull null
                val confidence = obj["confidence"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                val turnId = obj["turn_id"]?.toString()?.removeSurrounding("\"") ?: "unknown"
                val date = obj["date"]?.toString()?.removeSurrounding("\"") ?: ""
                if (confidence >= CONFIDENCE_THRESHOLD) {
                    ExtractedFact(fact = fact, confidence = confidence, turnId = turnId, date = date)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("failed to parse facts JSON: {}", e.message)
            emptyList()
        }
    }

    private fun appendFact(
        fact: ExtractedFact,
        nowMs: Long,
    ): Boolean {
        if (isDuplicate(fact.fact)) return false

        val provenance = "<!-- source: turn ${fact.turnId}, ${fact.date} -->"
        val line = "$provenance\n- ${fact.fact}"

        val memoryPath = Path.of(workspaceRoot, FACTS_FILE)
        val existing = if (Files.exists(memoryPath)) Files.readString(memoryPath) else ""

        val updated =
            if (existing.contains(FACTS_SECTION)) {
                existing.replace(FACTS_SECTION, "$FACTS_SECTION\n$line")
            } else {
                val section = "\n\n$FACTS_SECTION\n\n$line\n"
                existing + section
            }

        Files.writeString(memoryPath, updated)
        return true
    }

    private fun isDuplicate(fact: String): Boolean {
        val memoryPath = Path.of(workspaceRoot, FACTS_FILE)
        if (!Files.exists(memoryPath)) return false

        val content = Files.readString(memoryPath)
        val sectionStart = content.indexOf(FACTS_SECTION)
        if (sectionStart < 0) return false

        val section = content.substring(sectionStart)
        val factWords =
            fact
                .lowercase()
                .split(Regex("\\s+"))
                .filter { it.length > 3 }
                .toSet()
        if (factWords.isEmpty()) return false

        val bulletRegex = Regex("^\\s*-\\s+(.+)$", RegexOption.MULTILINE)
        val existingFacts = bulletRegex.findAll(section).map { it.groupValues[1] }.toList()

        for (existing in existingFacts) {
            val existingWords =
                existing
                    .lowercase()
                    .split(Regex("\\s+"))
                    .filter { it.length > 3 }
                    .toSet()
            if (existingWords.isEmpty()) continue
            val intersection = factWords.intersect(existingWords).size
            val union = factWords.union(existingWords).size
            val similarity = intersection.toDouble() / union
            if (similarity >= DUPLICATE_THRESHOLD) return true
        }
        return false
    }

    data class ExtractedFact(
        val fact: String,
        val confidence: Double,
        val turnId: String,
        val date: String,
    )

    data class MessageRow(
        val id: String,
        val role: String,
        val content: String,
        val ts: Long,
    )
}
