package org.tatrman.kantheon.golem.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.envelope.v1.PromptChip

/**
 * Heuristic chip builder — a faithful port of ai-platform `chips/heuristics.py`.
 *
 * **Package-coupling debt (flagged, not generalised):** these are hard-coded `ucetnictvi`
 * (Czech ERP accounting) column literals — `UCET_OBD` / `UCETNI_HODNOTA` / `KOD_STR` /
 * `KOD_UCTU`. They live here in the Golem arc (not in the constellation-wide envelope-render
 * lib) precisely because they are domain content. v1 is Czech-only; the locale arg is
 * accepted and ignored, mirroring `del locale  # v1 = Czech only`.
 */
object HeuristicChips {
    /** Pattern-result row cap upstream uses (`PATTERN_ROW_CAP`); row_count == this ⇒ "raise limit". */
    const val PATTERN_ROW_CAP: Int = 100

    @Suppress("UNUSED_PARAMETER")
    fun derive(
        rows: JsonArray,
        rowCount: Int,
        locale: String = "cs",
    ): List<PromptChip> {
        val chips = mutableListOf<PromptChip>()

        // UCET_OBD with >1 distinct values → offer a period comparison.
        if (RowUtil.hasColumn(rows, "UCET_OBD")) {
            val col = RowUtil.resolveColumn(rows, "UCET_OBD")!!
            if (RowUtil
                    .columnValues(rows, col)
                    .map { (it as? JsonPrimitive)?.content ?: it.toString() }
                    .distinct()
                    .size > 1
            ) {
                chips +=
                    chip(
                        "Porovnej s předchozím obdobím",
                        "A teď porovnej s předchozím obdobím",
                    )
            }
        }
        // UCETNI_HODNOTA with a small result → offer a descending sort.
        if (RowUtil.hasColumn(rows, "UCETNI_HODNOTA") && rowCount <= 50) {
            chips += chip("Seřaď podle účetní hodnoty sestupně", "Seřaď podle účetní hodnoty sestupně")
        }
        // KOD_STR → centre detail.
        if (RowUtil.hasColumn(rows, "KOD_STR")) {
            chips += chip("Detail střediska", "Detail střediska")
        }
        // KOD_UCTU → account detail.
        if (RowUtil.hasColumn(rows, "KOD_UCTU")) {
            chips += chip("Detail účtu", "Detail účtu")
        }
        // Hit the row cap → offer to narrow / raise the limit.
        if (rowCount == PATTERN_ROW_CAP) {
            chips += chip("Filtrovat / zvýšit limit", "Zúžit dotaz nebo zvýšit limit řádků")
        }
        return chips
    }

    private fun chip(
        display: String,
        prompt: String,
    ): PromptChip =
        PromptChip
            .newBuilder()
            .setDisplay(display)
            .setPrompt(prompt)
            .setSource("heuristic")
            .build()
}
