package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind

/**
 * Renders a turn's `envelope/v1` stream to a **channel** message (P4 S4.2 T3),
 * intentionally minimal in v1 (architecture §7): the conclusion text blocks + a
 * count of tables/charts + a deep link into the Iris session. **No chart rendering
 * in the channel** — Telegram gets counts + the link only. Pure (the channel send +
 * `telegrambots` wiring lives in the channels layer).
 */
object ConclusionRenderer {
    fun render(
        envelopes: List<FormatEnvelope>,
        deepLink: String,
    ): String {
        val text =
            envelopes
                .mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
                .joinToString("\n\n")
                .ifBlank { "(no text in the conclusion)" }
        val tables = envelopes.count { it.format.kind == FormatKind.TABLE }
        val charts = envelopes.count { it.format.kind == FormatKind.CHART }

        return buildString {
            append(text)
            if (tables > 0 || charts > 0) {
                append("\n\n")
                append(artifactCounts(tables, charts))
            }
            append("\n\n🔗 ").append(deepLink)
        }
    }

    private fun artifactCounts(
        tables: Int,
        charts: Int,
    ): String {
        val parts = mutableListOf<String>()
        if (tables > 0) parts += "$tables ${plural(tables, "table")}"
        if (charts > 0) parts += "$charts ${plural(charts, "chart")}"
        return "📊 ${parts.joinToString(", ")} — open in Iris"
    }

    private fun plural(
        n: Int,
        word: String,
    ): String = if (n == 1) word else "${word}s"
}
