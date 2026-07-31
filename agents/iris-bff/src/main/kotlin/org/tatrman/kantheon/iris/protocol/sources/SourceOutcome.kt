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

    /** The operator switched this source off — distinct from "we tried and failed". */
    data object SkippedByConfig : SourceOutcome<Nothing>
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
