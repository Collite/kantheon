package org.tatrman.kantheon.iris.protocol.sources

/**
 * What a source client returns. **Never an exception across the assembler
 * boundary** (architecture §2, P-4): a federated source being down is a fact the
 * document reports, not a failure of the request. Every client maps its own
 * failures into [Degraded] with a reason that becomes the receipt detail.
 */
sealed interface SourceOutcome<out T> {
    data class Ok<T>(
        val payload: T,
    ) : SourceOutcome<T>

    /** [reason] is operator-facing and lands verbatim in `SourceReceipt.detail`. */
    data class Degraded(
        val reason: String,
    ) : SourceOutcome<Nothing>

    /**
     * We deliberately did not consult this source — distinct from "we tried and failed".
     *
     * [reason] defaults to the operator-switched-off case, which is the common one, but
     * it is a parameter because *not every skip is a config decision*. The assembler
     * skips `translate-explain` when the turn carried its own plan (S-1: never
     * reconstruct what already exists), and reporting that as "disabled by config" told
     * the reader something false about their own deployment (review-080 R8). A skip
     * carries its own reason or it will be attributed to the last one somebody wrote.
     */
    data class SkippedByConfig(
        val reason: String = "source not configured",
    ) : SourceOutcome<Nothing>
}

/** Run [block], mapping any throw into [SourceOutcome.Degraded] tagged with [source]. */
internal inline fun <T> guardSource(
    source: String,
    block: () -> T,
): SourceOutcome<T> =
    runCatching { SourceOutcome.Ok(block()) }
        .getOrElse { e ->
            // CancellationException must propagate — the caller's coroutine is being
            // torn down, and swallowing it here would turn a cancelled request into a
            // slow one that still does work nobody wants.
            if (e is kotlinx.coroutines.CancellationException) throw e
            SourceOutcome.Degraded("$source: ${e.message ?: e::class.simpleName}")
        }
