package org.tatrman.kantheon.envelope.render.tables

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-column display directives, adapted from new-golem v2's
 * `_table_details_from_columns` (`nodes_v2/format.py:223`). v2 derived alignment
 * + float formatting from the executor's column **types**; in the LLM-catalog
 * path those types aren't available, so we infer them from the row **values**:
 *
 *  - a column is **numeric** when every non-null value is a JSON number →
 *    `alignment = "right"`;
 *  - it is additionally **float** when any value is non-integral → a rounded
 *    `number` intent (`{minimumFractionDigits:2, maximumFractionDigits:2,
 *    useGrouping:true}`, applied locale-aware by the FE via `Intl.NumberFormat`)
 *    plus the deprecated printf `format = "%.2f"` fallback.
 *
 * Integer columns are deliberately left raw (no grouping, no padding): codes,
 * IDs and years (`KOD_UCTU`, `ROK`) would be mangled by thousands separators —
 * the same carve-out v2 makes via `_FLOAT_TYPE_STEMS` vs `_NUMERIC_TYPE_STEMS`.
 *
 * Columns the caller already specified (non-null in [existing]) are left
 * untouched. The result is keyed by column name, stable in first-appearance order.
 */
fun inferColumnDirectives(
    content: JsonElement?,
    existing: Map<String, ColumnDirective> = emptyMap(),
): Map<String, ColumnDirective> {
    val rows: List<JsonObject> =
        when (content) {
            is JsonArray -> content.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(content)
            else -> emptyList()
        }
    if (rows.isEmpty()) return existing

    // First-appearance column order (mirrors header inference).
    val columns = LinkedHashSet<String>()
    rows.forEach { columns.addAll(it.keys) }

    val out = LinkedHashMap<String, ColumnDirective>()
    for (col in columns) {
        if (existing[col] != null) {
            out[col] = existing.getValue(col)
            continue
        }
        var sawValue = false
        var allNumeric = true
        var anyFloat = false
        for (row in rows) {
            val cell = row[col]
            if (cell == null || cell is JsonNull) continue
            sawValue = true
            val numeric = (cell as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            if (numeric == null) {
                allNumeric = false
                break
            }
            if (cell.content.let { it.contains('.') || it.contains('e', ignoreCase = true) }) anyFloat = true
        }
        if (sawValue && allNumeric) {
            out[col] =
                ColumnDirective(
                    alignment = "right",
                    number = if (anyFloat) ROUNDED_FLOAT else null,
                    format = if (anyFloat) "%.2f" else null,
                )
        }
    }
    return out
}

/** The default rounded-float `number` intent — 2 fraction digits, grouped (Δ5). */
val ROUNDED_FLOAT: NumberFormatIntent =
    NumberFormatIntent(minimumFractionDigits = 2, maximumFractionDigits = 2, useGrouping = true)

/** Subset of envelope/v1 `TableColumnSpec` this lib emits deterministically. */
data class ColumnDirective(
    val alignment: String? = null,
    val width: Int? = null,
    val hidden: Boolean? = null,
    val format: String? = null,
    /** Locale-aware number-format intent (envelope/v1 `NumberFormatSpec`); the FE applies it. */
    val number: NumberFormatIntent? = null,
)

/** Lib-local mirror of envelope/v1 `NumberFormatSpec`. */
data class NumberFormatIntent(
    val minimumFractionDigits: Int? = null,
    val maximumFractionDigits: Int? = null,
    val useGrouping: Boolean? = null,
)
