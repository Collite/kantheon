package org.tatrman.kantheon.envelope.render.tables

import kotlinx.serialization.json.JsonElement
import org.tatrman.kantheon.envelope.v1.NumberFormatSpec
import org.tatrman.kantheon.envelope.v1.TableColumnSpec
import org.tatrman.kantheon.envelope.v1.TableDetails
import org.tatrman.kantheon.envelope.v1.TableHeader

/**
 * Build a typed envelope/v1 [TableDetails] from row content — headers
 * ([inferTableHeaders]) plus per-column display directives ([inferColumnDirectives]):
 * numeric → right-aligned, float → a rounded `number` intent + `%.2f` fallback,
 * integers left raw (codes/IDs/years). The rows themselves stay the array `content`
 * on the block; this carries only the locale-independent display contract (Δ5).
 */
fun typedTableDetails(
    content: JsonElement?,
    existing: Map<String, ColumnDirective> = emptyMap(),
): TableDetails {
    val b = TableDetails.newBuilder()
    inferTableHeaders(content).forEach {
        b.addHeaders(TableHeader.newBuilder().setName(it.name).setTitle(it.title))
    }
    inferColumnDirectives(content, existing).forEach { (col, directive) ->
        b.putColumns(col, directive.toColumnSpec())
    }
    return b.build()
}

/** Convert a [ColumnDirective] to its envelope/v1 [TableColumnSpec] proto form. */
fun ColumnDirective.toColumnSpec(): TableColumnSpec {
    val c = TableColumnSpec.newBuilder()
    alignment?.let { c.alignment = it }
    width?.let { c.width = it }
    hidden?.let { c.hidden = it }
    format?.let { c.format = it }
    number?.let { n ->
        val nb = NumberFormatSpec.newBuilder()
        n.minimumFractionDigits?.let { nb.minimumFractionDigits = it }
        n.maximumFractionDigits?.let { nb.maximumFractionDigits = it }
        n.useGrouping?.let { nb.useGrouping = it }
        c.number = nb.build()
    }
    return c.build()
}
