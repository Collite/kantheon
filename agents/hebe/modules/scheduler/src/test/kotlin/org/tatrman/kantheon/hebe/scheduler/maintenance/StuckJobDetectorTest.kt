@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler.maintenance

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Timestamp

class StuckJobDetectorTest :
    StringSpec({
        "run with no stuck jobs returns zero" {
            val db = DbFactory.openInMemory()
            val repo = org.tatrman.kantheon.hebe.scheduler.JobRepo(db)
            val detector = StuckJobDetector(db, repo)

            val result = detector.run()

            result.isSuccess shouldBe true
            result.getOrNull() shouldBe 0
            db.close()
        }

        "run marks stuck jobs and returns count" {
            val db = DbFactory.openInMemory()
            val repo = org.tatrman.kantheon.hebe.scheduler.JobRepo(db)
            val oldTime = System.currentTimeMillis() - 60 * 60 * 1000
            insertRunningJob(db, "job-stuck-1", oldTime)

            val detector = StuckJobDetector(db, repo, timeoutMinutes = 30)
            val result = detector.run()

            result.isSuccess shouldBe true
            result.getOrNull() shouldBe 1

            val stuckJob = repo.loadJob("job-stuck-1")
            stuckJob?.status shouldBe "stuck"
            db.close()
        }

        "retryable stuck job creates new pending job" {
            val db = DbFactory.openInMemory()
            val repo = org.tatrman.kantheon.hebe.scheduler.JobRepo(db)
            val oldTime = System.currentTimeMillis() - 60 * 60 * 1000
            insertRunningJob(db, "job-retry-1", oldTime, payload = """{"retryable": true}""")

            val detector = StuckJobDetector(db, repo, timeoutMinutes = 30)
            val result = detector.run()

            result.isSuccess shouldBe true
            result.getOrNull() shouldBe 1

            val stuckJob = repo.loadJob("job-retry-1")
            stuckJob?.status shouldBe "stuck"
            db.close()
        }

        "non-retryable stuck job does not create new job" {
            val db = DbFactory.openInMemory()
            val repo = org.tatrman.kantheon.hebe.scheduler.JobRepo(db)
            val oldTime = System.currentTimeMillis() - 60 * 60 * 1000
            insertRunningJob(db, "job-no-retry", oldTime, payload = """{}""")

            val detector = StuckJobDetector(db, repo, timeoutMinutes = 30)
            val result = detector.run()

            result.isSuccess shouldBe true
            db.close()
        }
    })

private fun insertRunningJob(
    db: org.tatrman.kantheon.hebe.memory.db.Db,
    id: String,
    startedAt: Long,
    payload: String = "{}",
) {
    db.dataSource.connection.use { conn ->
        conn
            .prepareStatement(
                "INSERT INTO jobs(id, kind, status, started_at, trigger_at, payload_json, attempt) VALUES (?, ?, 'running', ?, ?, ?, 0)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, "adhoc")
                ps.setTimestamp(3, Timestamp(startedAt))
                ps.setTimestamp(4, Timestamp(startedAt))
                ps.setString(5, payload)
                ps.executeUpdate()
            }
    }
}
