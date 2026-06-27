@file:Suppress("MagicNumber", "NewLineAtEndOfFile", "TooGenericExceptionCaught", "UnusedPrivateProperty")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.scheduler.JobRepo
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class StuckJobDetector(
    private val db: Db,
    private val repo: JobRepo,
    private val timeoutMinutes: Long = 30,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ds: DataSource get() = db.dataSource

    companion object {
        const val DEFAULT_CRON = "*/5 * * * *"
    }

    data class Config(
        val cron: String = DEFAULT_CRON,
        val timeoutMinutes: Long = 30,
    )

    @Suppress("NestedBlockDepth")
    fun run(nowMs: Long = System.currentTimeMillis()): Result<Int> =
        try {
            val stuck = findStuckJobs(nowMs)
            var processed = 0
            for (job in stuck) {
                try {
                    markJobStuck(job, nowMs)
                    processed++
                    if (shouldRetry(job)) {
                        insertRetry(job, nowMs)
                    }
                } catch (e: Exception) {
                    logger.warn("failed to process stuck job {}: {}", job.id, e.message)
                }
            }
            Result.success(processed)
        } catch (e: Exception) {
            logger.error("stuck job detector failed: {}", e.message, e)
            Result.failure(e)
        }

    private fun findStuckJobs(nowMs: Long): List<StuckJobRow> {
        val cutoff = nowMs - timeoutMinutes * 60 * 1000L
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, kind, status, payload_json, result_json, attempt, started_at
                    FROM jobs
                    WHERE status = 'running' AND started_at < ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(cutoff))
                    val rs = ps.executeQuery()
                    val rows = mutableListOf<StuckJobRow>()
                    while (rs.next()) {
                        rows.add(
                            StuckJobRow(
                                id = rs.getString(1),
                                kind = rs.getString(2),
                                status = rs.getString(3),
                                payloadJson = rs.getString(4),
                                resultJson = rs.getString(5),
                                attempt = rs.getInt(6),
                                startedAt = rs.getTimestamp(7).time,
                            ),
                        )
                    }
                    return rows
                }
        }
    }

    private fun markJobStuck(
        job: StuckJobRow,
        nowMs: Long,
    ) {
        val existingResult = job.resultJson ?: "{}"
        val updatedResult = mergeStuckResult(existingResult)

        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE jobs
                    SET status = 'stuck', result_json = ?, ended_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, updatedResult)
                    ps.setTimestamp(2, Timestamp(nowMs))
                    ps.setString(3, job.id)
                    ps.executeUpdate()
                }
        }
    }

    private fun shouldRetry(job: StuckJobRow): Boolean {
        val payload = parsePayload(job.payloadJson)
        val retryable = payload["retryable"]?.lowercase() == "true"
        return retryable && job.attempt < 1
    }

    private fun insertRetry(
        job: StuckJobRow,
        nowMs: Long,
    ) {
        val newId = UUID.randomUUID().toString()
        val updatedPayload = addAttemptToPayload(job.payloadJson)

        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO jobs (id, kind, status, trigger_at, payload_json, attempt)
                    VALUES (?, ?, 'pending', ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, newId)
                    ps.setString(2, job.kind)
                    ps.setTimestamp(3, Timestamp(nowMs))
                    ps.setString(4, updatedPayload)
                    ps.executeUpdate()
                }
        }
        logger.info("stuck job {} retry scheduled as {}", job.id, newId)
    }

    private fun parsePayload(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val elem =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(json)
            val obj = elem as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
            obj.entries.associate { kv -> kv.key to kv.value.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mergeStuckResult(existing: String): String =
        try {
            val elem =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(existing)
            val obj = (elem as? kotlinx.serialization.json.JsonObject)?.toMutableMap() ?: mutableMapOf()
            obj["stuck"] = kotlinx.serialization.json.JsonPrimitive(true)
            kotlinx.serialization.json
                .JsonObject(obj)
                .toString()
        } catch (_: Exception) {
            """{"stuck": true}"""
        }

    private fun addAttemptToPayload(json: String?): String {
        if (json.isNullOrBlank()) return """{"attempt": 1}"""
        return try {
            val elem =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(json)
            val obj = (elem as? kotlinx.serialization.json.JsonObject)?.toMutableMap() ?: mutableMapOf()
            obj["attempt"] = kotlinx.serialization.json.JsonPrimitive(1)
            kotlinx.serialization.json
                .JsonObject(obj)
                .toString()
        } catch (_: Exception) {
            """{"attempt": 1}"""
        }
    }

    data class StuckJobRow(
        val id: String,
        val kind: String,
        val status: String,
        val payloadJson: String?,
        val resultJson: String?,
        val attempt: Int,
        val startedAt: Long,
    )
}
