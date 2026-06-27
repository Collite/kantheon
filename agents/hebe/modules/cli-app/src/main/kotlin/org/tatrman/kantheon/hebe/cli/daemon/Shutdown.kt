@file:Suppress("TooGenericExceptionCaught")

package org.tatrman.kantheon.hebe.cli.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

object Shutdown {
    private val log = LoggerFactory.getLogger(javaClass)
    private const val DRAIN_DEADLINE_MS = 30_000L

    fun installHook(
        scope: CoroutineScope,
        pidFile: PidFile,
        onShutdown: suspend () -> Unit,
    ) {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                log.info("shutdown signal received, draining (max {}s)", DRAIN_DEADLINE_MS / 1000)
                try {
                    val shutdownJob = scope.launch { onShutdown() }
                    runBlocking {
                        val result =
                            withTimeoutOrNull(DRAIN_DEADLINE_MS) {
                                shutdownJob.join()
                                true
                            }
                        if (result != true) {
                            log.warn("drain deadline exceeded ({}ms), forcing shutdown", DRAIN_DEADLINE_MS)
                            shutdownJob.cancel()
                        }
                    }
                } catch (e: Exception) {
                    log.error("error during shutdown: {}", e.message, e)
                } finally {
                    pidFile.close()
                }
                log.info("shutdown complete")
            }, "hebe-shutdown"),
        )
    }
}
