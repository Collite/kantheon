package org.tatrman.kantheon.envelope.render.catalog

import kotlinx.serialization.json.JsonElement
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec

/**
 * Inputs to the format catalog for one block.
 *
 * @param question     the verbatim user question (steers kind selection).
 * @param answerText   the agent's textual answer (the `text` for plaintext/markdown).
 * @param rows         structured result rows — a `JsonArray` of objects, a single
 *                     `JsonObject`, or `null` when the turn produced no data. Drives
 *                     the deterministic fallback (table when structured, plaintext when not).
 * @param desiredKind  a user-requested format (`/format chart`), or `null`. Recorded as
 *                     `fallbackFrom` when the catalog can't satisfy it.
 */
data class FormatRequest(
    val question: String,
    val answerText: String,
    val rows: JsonElement? = null,
    val desiredKind: FormatKind? = null,
)

/**
 * The rendered format for one block: a built envelope/v1 [FormatSpec] plus the
 * `text` / `content_json` payload. Callers assemble this into a `Block`
 * (provenance-stamped — see `BlockAssembler`) or a `FormatEnvelope`.
 *
 * @param fellBack      true when the deterministic fallback produced this result
 *                      (the `golem_format_fallback_total` counter source — G-21).
 * @param fallbackFrom  the kind we degraded from when [fellBack] (the requested
 *                      kind, or `FORMAT_KIND_UNSPECIFIED` when unknown).
 */
data class FormatResult(
    val kind: FormatKind,
    val text: String?,
    val contentJson: String?,
    val format: FormatSpec,
    val fellBack: Boolean = false,
    val fallbackFrom: FormatKind = FormatKind.FORMAT_KIND_UNSPECIFIED,
)

/**
 * A single format-tool attempt failed. The [reason] mirrors new-golem v2's
 * `last_error.reason` taxonomy so the retry prompt can echo it; the [message] is
 * fed verbatim into the next attempt's repair prompt.
 */
class FormatToolException(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason {
        /** No tool call returned (`tool_choice` not honoured). */
        NO_TOOL_CALL,

        /** A tool was named that isn't in the catalog. */
        UNKNOWN_TOOL,

        /** The tool args failed schema validation. */
        SCHEMA_INVALID,

        /** The underlying LLM call errored / timed out. */
        LLM_ERROR,
    }
}
