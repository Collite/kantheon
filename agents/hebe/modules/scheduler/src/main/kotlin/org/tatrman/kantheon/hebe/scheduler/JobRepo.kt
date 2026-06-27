@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.scheduler

import org.tatrman.kantheon.hebe.memory.db.Db
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Job(
    val id: String,
    val kind: String,
    val status: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val triggerAt: Long,
    val payloadJson: String?,
    val resultJson: String?,
    val attempt: Int,
)

class JobRepo(
    private val db: Db,
) {
    private val ds: DataSource get() = db.dataSource

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    fun claimPending(
        now: Long,
        maxN: Int = 1,
    ): List<Job> {
        ds.connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                conn
                    .prepareStatement(
                        """
                        UPDATE jobs
                        SET status = 'running', started_at = ?
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE status = 'pending' AND trigger_at <= ?
                            ORDER BY trigger_at
                            LIMIT ?
                        )
                        RETURNING id, kind, status, started_at, ended_at, trigger_at, payload_json, result_json, attempt
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setTimestamp(1, Timestamp(now))
                        ps.setLong(2, now)
                        ps.setInt(3, maxN)
                        val rs = ps.executeQuery()
                        val jobs = mutableListOf<Job>()
                        while (rs.next()) {
                            jobs.add(readJob(rs))
                        }
                        conn.commit()
                        return jobs
                    }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun updateStatus(
        jobId: String,
        status: String,
        resultJson: String?,
        endedAt: Long,
    ) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE jobs
                    SET status = ?, result_json = ?, ended_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setString(2, resultJson)
                    ps.setTimestamp(3, Timestamp(endedAt))
                    ps.setString(4, jobId)
                    ps.executeUpdate()
                }
        }
    }

    fun insertPending(
        kind: String,
        triggerAt: Long,
        payloadJson: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO jobs (id, kind, status, trigger_at, payload_json, attempt)
                    VALUES (?, ?, 'pending', ?, ?, 0)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, kind)
                    ps.setTimestamp(3, Timestamp(triggerAt))
                    ps.setString(4, payloadJson)
                    ps.executeUpdate()
                }
        }
        return id
    }

    fun loadRoutine(routineId: String): RoutineRow? {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, name, cron, body_kind, body_ref, body_json, enabled, created_at, last_run_at, next_run_at
                    FROM routines WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, routineId)
                    val rs = ps.executeQuery()
                    if (!rs.next()) return null
                    val lr9 = rs.getLong(9)
                    val lastRunAtVal: Long? = if (rs.wasNull()) null else lr9
                    val lr10 = rs.getLong(10)
                    val nextRunAtVal: Long? = if (rs.wasNull()) null else lr10
                    return RoutineRow(
                        id = rs.getString(1),
                        name = rs.getString(2),
                        cron = rs.getString(3),
                        bodyKind = rs.getString(4),
                        bodyRef = rs.getString(5),
                        bodyJson = rs.getString(6),
                        enabled = rs.getInt(7) == 1,
                        createdAt = rs.getLong(8),
                        lastRunAt = lastRunAtVal,
                        nextRunAt = nextRunAtVal,
                    )
                }
        }
    }

    private fun readJob(rs: java.sql.ResultSet): Job =
        Job(
            id = rs.getString(1),
            kind = rs.getString(2),
            status = rs.getString(3),
            startedAt = rs.getTimestamp(4)?.time,
            endedAt = rs.getTimestamp(5)?.time,
            triggerAt = rs.getTimestamp(6).time,
            payloadJson = rs.getString(7),
            resultJson = rs.getString(8),
            attempt = rs.getInt(9),
        )

    fun loadJob(jobId: String): Job? {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, kind, status, started_at, ended_at, trigger_at, payload_json, result_json, attempt
                    FROM jobs WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, jobId)
                    val rs = ps.executeQuery()
                    if (!rs.next()) return null
                    return readJob(rs)
                }
        }
    }

    fun loadEnabledRoutines(): List<RoutineRow> {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT id, name, cron, body_kind, body_ref, body_json, enabled, created_at, last_run_at, next_run_at
                    FROM routines WHERE enabled = 1
                    """.trimIndent(),
                ).use { ps ->
                    val rs = ps.executeQuery()
                    return readRoutines(rs)
                }
        }
    }

    fun updateRoutineNextRun(
        routineId: String,
        lastRunAt: Long,
        nextRunAt: Long,
    ) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    UPDATE routines SET last_run_at = ?, next_run_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setTimestamp(1, Timestamp(lastRunAt))
                    ps.setTimestamp(2, Timestamp(nextRunAt))
                    ps.setString(3, routineId)
                    ps.executeUpdate()
                }
        }
    }

    private fun readRoutines(rs: java.sql.ResultSet): List<RoutineRow> {
        val routines = mutableListOf<RoutineRow>()
        while (rs.next()) {
            val lr9 = rs.getLong(9)
            val lastRunAt: Long? = if (rs.wasNull()) null else lr9
            val lr10 = rs.getLong(10)
            val nextRunAt: Long? = if (rs.wasNull()) null else lr10
            routines.add(
                RoutineRow(
                    id = rs.getString(1),
                    name = rs.getString(2),
                    cron = rs.getString(3),
                    bodyKind = rs.getString(4),
                    bodyRef = rs.getString(5),
                    bodyJson = rs.getString(6),
                    enabled = rs.getInt(7) == 1,
                    createdAt = rs.getLong(8),
                    lastRunAt = lastRunAt,
                    nextRunAt = nextRunAt,
                ),
            )
        }
        return routines
    }
}

data class RoutineRow(
    val id: String,
    val name: String,
    val cron: String,
    val bodyKind: String,
    val bodyRef: String,
    val bodyJson: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
)

fun Job.payload(): Map<String, String> {
    val raw = payloadJson ?: return emptyMap()
    return try {
        val elem = Json.parseToJsonElement(raw)
        val obj =
            elem as? kotlinx.serialization.json.JsonObject
                ?: return emptyMap()
        obj.entries.associate { kv -> kv.key to kv.value.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun errorResult(error: String): String =
    buildJsonObject {
        put("error", JsonPrimitive(error))
    }.toString()
