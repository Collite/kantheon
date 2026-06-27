package org.tatrman.kantheon.envelope.render.tables

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Port of new-golem v2 `infer_table_headers` (ai-platform golem
 * `format_catalog.py:248` @ git 5281954d^).
 *
 * Derives table headers from row content when the LLM omitted them: the **union
 * of object keys across all rows, ordered by first appearance** (stable; rows
 * beyond the first contribute only keys not yet seen). A single object yields its
 * own keys. `title` is the raw key VERBATIM — the Python does no
 * snake_case→Title-Case transform, and neither do we (parity invariant).
 *
 * Non-object rows are skipped; an empty / non-structured input yields `[]`.
 */
fun inferTableHeaders(content: JsonElement?): List<InferredHeader> {
    val seen = LinkedHashSet<String>()
    when (content) {
        is JsonArray ->
            content.forEach { row ->
                if (row is JsonObject) seen.addAll(row.keys)
            }
        is JsonObject -> seen.addAll(content.keys)
        else -> Unit
    }
    return seen.map { InferredHeader(name = it, title = it) }
}

/** A `{name, title}` pair — maps 1:1 onto envelope/v1 `TableHeader`. */
data class InferredHeader(
    val name: String,
    val title: String,
)
