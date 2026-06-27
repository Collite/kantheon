@file:Suppress("NewLineAtEndOfFile", "TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class Scheduler(
    private val repo: JobRepo,
    private val runner: JobRunner,
    private val routinesEngine: RoutinesEngine,
    private val routineTickInterval: Duration = 30.seconds,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val jobLoop = JobLoop(repo, runner)

    fun start(scope: CoroutineScope) {
        scope.launch { runRoutinesLoop() }
        jobLoop.run(scope)
        logger.info("scheduler started")
    }

    private suspend fun runRoutinesLoop() {
        while (true) {
            try {
                routinesEngine.tick(System.currentTimeMillis())
            } catch (e: Exception) {
                logger.error("routines engine tick failed: {}", e.message, e)
            }
            delay(routineTickInterval)
        }
    }
}
