package org.tatrman.kantheon.envelope.render.catalog

import kotlinx.serialization.json.JsonArray

/**
 * Builds the system + user prompts for the format catalog, porting the selection
 * guidance from new-golem v2's `format_catalog.py` `system_prompt()` /
 * `user_prompt()` (@ git 5281954d^). Kept deliberately terse — the gotcha
 * behaviours are enforced deterministically by [org.tatrman.kantheon.envelope.render.fallback.FormatCatalog],
 * not by prompt wording.
 */
class FormatPrompt(
    private val rowPreviewCap: Int = 50,
) {
    fun system(): String =
        """
        |You format an agent's answer for display. Pick EXACTLY ONE render tool and
        |reply with a single JSON object: {"tool": "<ToolName>", ...args}. No prose.
        |
        |Tools:
        | - RenderPlaintext {"text"}            — a short single paragraph.
        | - RenderMarkdown  {"text"}            — narrative / lists / headings / code / mermaid.
        | - RenderTable     {"text"?, "content", "details"?}
        |                                       — a list of objects (or one object) to READ.
        |                                         Copy the structured rows VERBATIM into `content`;
        |                                         do not summarise, drop, or re-order columns.
        | - RenderChart     {"text"?, "content", "intent"}
        |                                       — to SEE a trend/comparison/distribution. `intent`
        |                                         has {kind: line|bar|pie|scatter|area, x, y:[...]}.
        |                                         A chart needs ≥1 numeric measure column; id/code-only
        |                                         rows do NOT qualify — use RenderTable instead.
        """.trimMargin()

    fun user(
        request: FormatRequest,
        priorError: String?,
    ): String =
        buildString {
            append("Question:\n${request.question}\n\n")
            append("Answer:\n${request.answerText}\n")
            request.desiredKind?.let {
                append("\nRequested format: ${it.name} (use it only if the data shape supports it).\n")
            }
            request.rows?.let { rows ->
                val preview =
                    if (rows is JsonArray && rows.size > rowPreviewCap) {
                        val capped = JsonArray(rows.take(rowPreviewCap)).toString()
                        "$capped\n(showing first $rowPreviewCap of ${rows.size} rows)"
                    } else {
                        rows.toString()
                    }
                append("\nStructured rows (copy verbatim into table/chart content):\n$preview\n")
            }
            priorError?.let {
                append("\nYour previous reply failed: $it\nFix the arguments and reply again.\n")
            }
        }
}
