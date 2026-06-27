package org.tatrman.kantheon.golem.execution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.persistence.GolemTurnRecord
import org.tatrman.kantheon.golem.persistence.TurnsRepository
import org.tatrman.kantheon.golem.v1.RowSelection

private val log = LoggerFactory.getLogger(SelectionResolver::class.java)

/**
 * The rows a row-detail selection refers to, resolved server-side from history
 * (S2.4 §10 Δ4). [selectionContext] is the **first** selected row flattened to
 * `{column: value}` — the binding source `pick_plan` fills unfilled pattern params
 * from (`_bind_selection_args`).
 */
data class ResolvedSelection(
    val selectedRows: List<JsonObject>,
    val selectionContext: JsonObject,
)

/**
 * Resolves a [RowSelection] (`{bubble_id, row_indices}`) against `golem_turns`
 * history. The FE sends only a reference — never the row data — so Golem looks up
 * the producing turn by `bubble_id`, reads back the rows it displayed for that
 * bubble, and picks the referenced indices. Returns null (and logs) when the bubble
 * is stale (no such turn / envelope) or every index is out of range — the turn then
 * proceeds with no selection rather than failing.
 */
class SelectionResolver(
    private val turns: TurnsRepository?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(
        selection: RowSelection?,
        userId: String,
        tenantId: String,
    ): ResolvedSelection? {
        if (selection == null || selection.bubbleId.isBlank() || turns == null) return null
        // Scoped to the caller (H2): a client-supplied bubble_id never reads another user's rows.
        val turn = turns.findByBubbleId(selection.bubbleId, userId, tenantId)
        if (turn == null) {
            log.info("selection_stale: no turn for bubble '{}'", selection.bubbleId)
            return null
        }
        val rows = rowsForBubble(turn, selection.bubbleId)
        if (rows.isEmpty()) {
            log.info("selection_stale: no rows for bubble '{}'", selection.bubbleId)
            return null
        }
        val picked = selection.rowIndicesList.filter { it in rows.indices }.map { rows[it] }
        if (picked.isEmpty()) {
            log.info("selection_out_of_range: indices {} vs {} rows", selection.rowIndicesList, rows.size)
            return null
        }
        return ResolvedSelection(selectedRows = picked, selectionContext = picked.first())
    }

    /**
     * The rows the turn displayed for [bubbleId]. The persisted `envelopes_json` is a JSON array of
     * proto3-JSON [org.tatrman.kantheon.envelope.v1.FormatEnvelope]s; the matching envelope's
     * `contentJson` is itself a JSON string holding the rows array (the executor writes
     * `content_json = rows.toString()`). Empty when nothing parses.
     */
    private fun rowsForBubble(
        turn: GolemTurnRecord,
        bubbleId: String,
    ): List<JsonObject> {
        val envelopes =
            runCatching { json.parseToJsonElement(turn.envelopesJson).jsonArray }.getOrNull() ?: return emptyList()
        val match =
            envelopes
                .firstOrNull { (it as? JsonObject)?.get("bubbleId")?.jsonPrimitive?.content == bubbleId }
                ?: return emptyList()
        val contentJson = (match as? JsonObject)?.get("contentJson")?.jsonPrimitive?.content ?: return emptyList()
        return runCatching {
            json
                .parseToJsonElement(contentJson)
                .jsonArray
                .mapNotNull { it as? JsonObject }
        }.getOrElse { emptyList() }
    }

    companion object {
        /** A resolver that resolves nothing — the default when no history is wired (tests, skeleton boot). */
        val NONE = SelectionResolver(null)
    }
}
