@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.scheduler

import org.tatrman.kantheon.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class JobRepoTest :
    StringSpec({
        "insertPending returns a uuid" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val triggerAt = System.currentTimeMillis()
            val id = repo.insertPending("adhoc", triggerAt, """{"prompt":"hello"}""")

            id shouldNotBe null
            id.length shouldBe 36
            db.close()
        }

        "claimPending picks up past-due job" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            val id = repo.insertPending("adhoc", now - 1000, """{"prompt":"hello"}""")

            val jobs = repo.claimPending(now, maxN = 1)
            jobs.size shouldBe 1
            jobs[0].id shouldBe id
            jobs[0].status shouldBe "running"
            db.close()
        }

        "claimPending ignores future trigger_at" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            repo.insertPending("adhoc", now + 10_000, """{"prompt":"future"}""")

            val jobs = repo.claimPending(now, maxN = 1)
            jobs.size shouldBe 0
            db.close()
        }

        "updateStatus marks job done" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            val id = repo.insertPending("adhoc", now - 1000, """{"prompt":"hello"}""")

            repo.claimPending(now, maxN = 1)
            repo.updateStatus(id, "done", null, System.currentTimeMillis())

            val job = repo.loadJob(id)
            job?.status shouldBe "done"
            db.close()
        }

        "routine payload parsed correctly" {
            val db = DbFactory.openInMemory()
            val repo = JobRepo(db)
            val now = System.currentTimeMillis()
            repo.insertPending("routine", now, """{"routine_id":"abc-123"}""")

            val jobs = repo.claimPending(now, maxN = 1)
            jobs[0].payload()["routine_id"] shouldBe "abc-123"
            db.close()
        }
    })
