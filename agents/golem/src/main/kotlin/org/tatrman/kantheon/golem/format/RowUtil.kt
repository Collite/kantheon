package org.tatrman.kantheon.golem.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared helpers over a turn's row array (the executor's `content` JSON). */
object RowUtil {
    /** Column names in first-appearance order across all rows. */
    fun columnNames(rows: JsonArray): List<String> {
        val cols = LinkedHashSet<String>()
        rows.filterIsInstance<JsonObject>().forEach { cols.addAll(it.keys) }
        return cols.toList()
    }

    /** Non-null values for [col] across rows. */
    fun columnValues(
        rows: JsonArray,
        col: String,
    ): List<JsonElement> = rows.filterIsInstance<JsonObject>().mapNotNull { it[col] }

    /** True when any row carries [name] (case-insensitive). */
    fun hasColumn(
        rows: JsonArray,
        name: String,
    ): Boolean = columnNames(rows).any { it.equals(name, ignoreCase = true) }

    /** The actual column key matching [name] case-insensitively, or null. */
    fun resolveColumn(
        rows: JsonArray,
        name: String,
    ): String? = columnNames(rows).firstOrNull { it.equals(name, ignoreCase = true) }
}
