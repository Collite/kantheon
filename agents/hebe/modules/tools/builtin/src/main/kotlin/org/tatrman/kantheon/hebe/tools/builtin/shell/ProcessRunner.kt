package org.tatrman.kantheon.hebe.tools.builtin.shell

import org.tatrman.kantheon.hebe.security.estop.EmergencyStop
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

data class ProcessResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

object ProcessRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val DEFAULT_TIMEOUT_MS = 60_000L
    private const val MAX_TIMEOUT_MS = 600_000L
    private const val MAX_OUTPUT_CHARS = 1_000_000
    private const val GRACEFUL_KILL_MS = 1_000L
    private const val POLL_INTERVAL_MS = 100L

    suspend fun run(
        cmd: String,
        cwd: Path? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
        emergencyStop: EmergencyStop? = null,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            val effectiveTimeout = timeoutMs.coerceIn(0L, MAX_TIMEOUT_MS)
            logger.debug("running shell command: {} (timeout={}ms)", cmd, effectiveTimeout)

            val processBuilder = ProcessBuilder("bash", "-c", cmd)
            cwd?.let { processBuilder.directory(it.toFile()) }
            val fullEnv = processBuilder.environment()
            fullEnv.putAll(env)
            fullEnv.remove("JAVA_TOOL_OPTIONS")

            val process = processBuilder.start()

            val stdoutCapture = StringBuilder()
            val stderrCapture = StringBuilder()
            val stdoutThread = Thread { stdoutCapture.append(process.inputStream.bufferedReader().readText()) }
            val stderrThread = Thread { stderrCapture.append(process.errorStream.bufferedReader().readText()) }
            stdoutThread.start()
            stderrThread.start()

            val startNs = System.nanoTime()
            var exitCode = -1
            var timedOut = false

            // Polling inside Dispatchers.IO: Thread.sleep is fine on an IO thread.
            // Polling every 100ms keeps EmergencyStop responsiveness without busy-spin.
            while (process.isAlive) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                if (elapsedMs >= effectiveTimeout) {
                    logger.debug("shell timed out after {}ms", elapsedMs)
                    process.destroy()
                    try {
                        Thread.sleep(GRACEFUL_KILL_MS)
                    } catch (_: Exception) {
                    }
                    if (process.isAlive) process.destroyForcibly()
                    stdoutThread.interrupt()
                    stderrThread.interrupt()
                    stdoutThread.join(500)
                    stderrThread.join(500)
                    timedOut = true
                    break
                }
                if (emergencyStop?.isStopRequested == true) {
                    logger.info("stop requested, killing process")
                    process.destroy()
                    try {
                        Thread.sleep(GRACEFUL_KILL_MS)
                    } catch (_: Exception) {
                    }
                    if (process.isAlive) process.destroyForcibly()
                    break
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }

            if (!timedOut && !process.isAlive) {
                exitCode = process.exitValue()
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            if (timedOut) {
                ProcessResult("", "timeout after ${effectiveTimeout}ms", -1, timedOut = true)
            } else {
                ProcessResult(
                    truncate(stdoutCapture.toString()),
                    truncate(stderrCapture.toString()),
                    exitCode,
                )
            }
        }

    private fun truncate(s: String): String {
        if (s.length > MAX_OUTPUT_CHARS) {
            return s.take(MAX_OUTPUT_CHARS) + "\n[truncated]"
        }
        return s
    }
}
