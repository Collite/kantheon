package org.tatrman.kantheon.iris.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Pure BFF-side table shaping (Stage 3.2 T1): sort / filter / paginate over the
 * cached rows of a table envelope, with no agent round-trip. The rows are the
 * JSON objects of the producing turn's `content_json`; the column key is the
 * `TableHeader.name` (the row-object key). Numeric values compare numerically,
 * everything else by string.
 */
object TableShaping {
    /** The §1.1 TableFilterSpec operator set; [matches]/parse reject anything else. */
    val VALID_OPERATORS = setOf("eq", "neq", "lt", "lte", "gt", "gte", "contains", "in")

    /** Upper bound on a single requested page size (defensive — avoids absurd slices). */
    const val MAX_PAGE_SIZE = 10_000

    data class Sort(
        val column: String,
        val direction: String,
    )

    data class Filter(
        val column: String,
        val operator: String,
        val value: JsonElement,
    )

    /** Apply filters (all must pass), then sort, then the page slice. */
    fun shape(
        rows: List<JsonObject>,
        filters: List<Filter>,
        sort: Sort?,
        page: Int?,
        pageSize: Int?,
    ): List<JsonObject> {
        var out = rows
        for (f in filters) out = out.filter { matches(it[f.column], f.operator, f.value) }
        if (sort != null) out = applySort(out, sort)
        if (page != null && pageSize != null && pageSize > 0) {
            // Long math: page/pageSize are caller-supplied; an Int multiply could
            // overflow negative and throw on subList (see TypedActionDispatcher.parse,
            // which also bounds the inputs at the edge).
            val fromLong = (page - 1).coerceAtLeast(0).toLong() * pageSize
            out =
                if (fromLong >= out.size) {
                    emptyList()
                } else {
                    val from = fromLong.toInt()
                    out.subList(from, minOf(from + pageSize, out.size))
                }
        }
        return out
    }

    private fun applySort(
        rows: List<JsonObject>,
        sort: Sort,
    ): List<JsonObject> {
        val cmp =
            Comparator<JsonObject> { a, b ->
                val av = a[sort.column]
                val bv = b[sort.column]
                val an = num(av)
                val bn = num(bv)
                if (an != null && bn != null) an.compareTo(bn) else str(av).compareTo(str(bv))
            }
        return rows.sortedWith(if (sort.direction.equals("desc", ignoreCase = true)) cmp.reversed() else cmp)
    }

    /** The §1.1 TableFilterSpec operators: eq|neq|lt|lte|gt|gte|contains|in. */
    private fun matches(
        cell: JsonElement?,
        operator: String,
        value: JsonElement,
    ): Boolean {
        if (operator == "in") {
            val set = (value as? JsonArray)?.map { str(it) }?.toSet() ?: return false
            return str(cell) in set
        }
        val cn = num(cell)
        val vn = num(value)
        val numeric = cn != null && vn != null
        return when (operator) {
            "eq" -> if (numeric) cn == vn else str(cell) == str(value)
            "neq" -> if (numeric) cn != vn else str(cell) != str(value)
            "lt" -> if (numeric) cn!! < vn!! else str(cell) < str(value)
            "lte" -> if (numeric) cn!! <= vn!! else str(cell) <= str(value)
            "gt" -> if (numeric) cn!! > vn!! else str(cell) > str(value)
            "gte" -> if (numeric) cn!! >= vn!! else str(cell) >= str(value)
            "contains" -> str(cell).contains(str(value), ignoreCase = true)
            else -> false
        }
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

    private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull ?: (e?.toString() ?: "")
}
