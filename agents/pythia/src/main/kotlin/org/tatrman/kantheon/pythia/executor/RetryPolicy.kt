package org.tatrman.kantheon.pythia.executor

import kotlinx.coroutines.delay
import java.util.concurrent.ThreadLocalRandom

/**
 * Tiered retry (architecture §5): TRANSIENT failures retry with jittered
 * exponential backoff up to [maxAttempts]; PERMANENT / SYSTEMIC propagate
 * immediately. The default jitter is real full-jitter (`[0, backoff]`) so retries
 * across nodes don't fire in lockstep (thundering herd); it is injectable so tests
 * stay deterministic (and `delay` runs on virtual time under `runTest`).
 */
class RetryPolicy(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 20,
    private val jitter: (Long) -> Long = { ms -> if (ms <= 0) 0 else ThreadLocalRandom.current().nextLong(ms + 1) },
) {
    suspend fun <T> withRetry(
        onRetry: (attempt: Int, reason: String) -> Unit,
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: NodeExecutionException) {
                if (e.kind != FailureKind.TRANSIENT || attempt + 1 >= maxAttempts) throw e
                attempt++
                onRetry(attempt, e.message ?: "transient failure")
                // Cap the shift so a large maxAttempts can't overflow the Long backoff.
                val backoff = baseDelayMs shl (attempt - 1).coerceAtMost(32)
                delay(jitter(backoff))
            }
        }
    }
}
