package org.tatrman.kantheon.hebe.scheduler

import org.tatrman.kantheon.hebe.scheduler.cron.CronParser
import org.tatrman.kantheon.hebe.scheduler.cron.nextFire
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

@Suppress("TooGenericExceptionCaught")
class RoutinesEngine(
    private val repo: JobRepo,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val utc = TimeZone.UTC

    fun tick(nowMs: Long) {
        val routines = loadEnabledRoutines()
        for (routine in routines) {
            try {
                processRoutine(routine, nowMs)
            } catch (e: Exception) {
                logger.error("routine={} tick failed: {}", routine.id, e.message, e)
            }
        }
    }

    private fun processRoutine(
        routine: RoutineRow,
        nowMs: Long,
    ) {
        val nextRun = routine.nextRunAt ?: (nowMs - 1)
        if (nextRun > nowMs) return

        val cron = CronParser.parse(routine.cron)
        val nowInstant = kotlin.time.Instant.fromEpochMilliseconds(nowMs)
        val nextInstant = cron.nextFire(nowInstant, utc)
        val nextRunAt = nextInstant.toEpochMilliseconds()

        val isCatchup = routine.lastRunAt != null && isCatchupDue(routine, nowMs)

        val payload =
            buildJsonObject {
                put("routine_id", routine.id)
                if (isCatchup) put("catchup", "true")
            }

        repo.insertPending("routine", nowMs, payload.toString())

        repo.updateRoutineNextRun(routine.id, nowMs, nextRunAt)

        logger.info("routine={} scheduled next_run_at={}", routine.id, nextRunAt)
    }

    private fun isCatchupDue(
        routine: RoutineRow,
        nowMs: Long,
    ): Boolean {
        val lastRun = routine.lastRunAt ?: return false
        val cron = CronParser.parse(routine.cron)
        val lastInstant = kotlin.time.Instant.fromEpochMilliseconds(lastRun)
        val nextIfOnTime = cron.nextFire(lastInstant, utc).toEpochMilliseconds()
        return nextIfOnTime < nowMs
    }

    private fun loadEnabledRoutines(): List<RoutineRow> = repo.loadEnabledRoutines()
}
