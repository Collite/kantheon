package org.tatrman.kantheon.hebe.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class JobLoop(
    private val repo: JobRepo,
    private val runner: JobRunner,
    private val tickInterval: Duration = 5.seconds,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun run(scope: CoroutineScope) {
        scope.launch {
            loop()
        }
    }

    private suspend fun loop() {
        while (true) {
            val now = System.currentTimeMillis()
            val jobs = repo.claimPending(now, maxN = 1)
            for (job in jobs) {
                logger.debug("job={} picked up, kind={}", job.id, job.kind)
                val success = runner.run(job)
                val endedAt = System.currentTimeMillis()
                if (success) {
                    repo.updateStatus(job.id, "done", null, endedAt)
                    logger.info("job={} done", job.id)
                } else {
                    val err = errorResult("job failed")
                    repo.updateStatus(job.id, "failed", err, endedAt)
                    logger.warn("job={} failed", job.id)
                }
            }
            delay(tickInterval)
        }
    }
}
