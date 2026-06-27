@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler

import org.tatrman.kantheon.hebe.memory.db.Db
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Timestamp

class RoutinesEngineTest :
    StringSpec({
        "tick inserts job when next_run_at is due" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            insertRoutine(db, RoutineParams(id = "r1", cron = "@hourly", nextRunAt = System.currentTimeMillis() - 60_000))

            val engine = RoutinesEngine(repo)
            engine.tick(System.currentTimeMillis())

            val job = findJob(db, "r1")
            job shouldNotBe null
            db.close()
        }

        "tick does not insert job when next_run_at is in future" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            insertRoutine(db, RoutineParams(id = "r1", cron = "@hourly", nextRunAt = System.currentTimeMillis() + 3_600_000))

            val engine = RoutinesEngine(repo)
            engine.tick(System.currentTimeMillis())

            countJobs(db) shouldBe 0
            db.close()
        }

        "tick updates next_run_at after scheduling job" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            insertRoutine(db, RoutineParams(id = "r1", cron = "@hourly", nextRunAt = now - 60_000, lastRunAt = now - 3_600_000))

            val engine = RoutinesEngine(repo)
            engine.tick(now)

            val next = getNextRunAt(db, "r1")
            next shouldNotBe null
            assert(next!! > now)
            db.close()
        }

        "tick ignores disabled routines" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            insertRoutine(db, RoutineParams(id = "r1", cron = "@hourly", nextRunAt = System.currentTimeMillis() - 60_000, enabled = false))

            val engine = RoutinesEngine(repo)
            engine.tick(System.currentTimeMillis())

            countJobs(db) shouldBe 0
            db.close()
        }

        "catchup job is inserted when last_run_at is old" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            insertRoutine(db, RoutineParams(id = "r1", cron = "@hourly", nextRunAt = now - 120_000, lastRunAt = now - 7200_000))

            val engine = RoutinesEngine(repo)
            engine.tick(now)

            val job = findJob(db, "r1")
            job shouldNotBe null
            job!!.payload()["catchup"] shouldBe "true"
            db.close()
        }
    })

private fun insertRoutine(
    db: Db,
    params: RoutineParams,
) {
    db.dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                """
                INSERT INTO routines (id, name, cron, body_kind, body_ref, body_json, enabled, created_at, last_run_at, next_run_at)
                VALUES (?, ?, ?, 'tool', 'test_tool', null, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, params.id)
                ps.setString(2, "Test Routine ${params.id}")
                ps.setString(3, params.cron)
                ps.setInt(4, if (params.enabled) 1 else 0)
                ps.setTimestamp(5, Timestamp(System.currentTimeMillis()))
                ps.setTimestamp(6, params.lastRunAt?.let { Timestamp(it) } ?: null)
                ps.setTimestamp(7, Timestamp(params.nextRunAt))
                ps.executeUpdate()
            }
    }
}

private data class RoutineParams(
    val id: String,
    val cron: String,
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val enabled: Boolean = true,
)

private fun findJob(
    db: Db,
    routineId: String,
): Job? {
    db.dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                "SELECT id, kind, status, started_at, ended_at, trigger_at, payload_json, result_json, attempt FROM jobs WHERE payload_json LIKE ?",
            ).use { ps ->
                ps.setString(1, "%$routineId%")
                val rs = ps.executeQuery()
                if (!rs.next()) return null
                return Job(
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
            }
    }
}

private fun countJobs(db: Db): Int {
    db.dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM jobs").use { ps ->
            val rs = ps.executeQuery()
            rs.next()
            return rs.getInt(1)
        }
    }
}

private fun getNextRunAt(
    db: Db,
    routineId: String,
): Long? {
    db.dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT next_run_at FROM routines WHERE id = ?").use { ps ->
            ps.setString(1, routineId)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            val v = rs.getTimestamp(1)
            return if (rs.wasNull()) null else v.time
        }
    }
}
