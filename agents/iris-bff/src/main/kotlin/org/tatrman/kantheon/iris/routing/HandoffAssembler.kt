package org.tatrman.kantheon.iris.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.TurnRecord

/**
 * PD-1 assembly (iris/v1 comment; contracts §1.2). On EVERY dispatch the BFF
 * builds a [HandoffContext] from the previous turn (`source_agent_id`,
 * `source_turn_ref = artifact_ref`, `user_question`, `current_view`,
 * `applied_context`) + the session entity context, and sends it both to Themis
 * (as `prior_context` — THIS is `themis_prior_context`) and to the routed agent.
 *
 * Size guard (cohesion review §4.10b / envelope/v1 note): cap `entities` at 50
 * bindings (most-relevant first) and truncate `suggested_focus` at 1 KiB so the
 * inline payload stays bounded without a peer artifact-read API.
 *
 * `current_view` / `applied_context` carryover from the previous turn lands with
 * the PD-4 echo wiring in Stage 3.2; here entities come from the session entity
 * context only (`current_view` left unset until the turn snapshot is populated).
 */
object HandoffAssembler {
    const val MAX_ENTITIES = 50
    const val MAX_FOCUS_BYTES = 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Build the handoff anchored on [previousTurn]. Returns `null` on the first
     * turn of a session (no anchor turn → absent handoff, per the assembly rule).
     */
    fun fromPreviousTurn(
        session: SessionRecord,
        previousTurn: TurnRecord?,
        suggestedFocus: String = "",
    ): HandoffContext? {
        if (previousTurn == null) return null
        val builder =
            HandoffContext
                .newBuilder()
                .setSourceAgentId(previousTurn.agentId)
                .setSourceTurnRef(previousTurn.artifactRef ?: "")
                .setUserQuestion(previousTurn.question)
                .addAllEntities(parseEntities(session.entityContextJson))
        if (suggestedFocus.isNotEmpty()) {
            builder.suggestedFocus = truncateUtf8(suggestedFocus, MAX_FOCUS_BYTES)
        }
        return builder.build()
    }

    /**
     * Parse the persisted session entity context (an array of envelope
     * `EntityContextSnapshot`s — `entity_type` / `entity_id` / `display_label`)
     * into [EntityBinding]s, capped at [MAX_ENTITIES]. Defensive: malformed
     * entries are skipped, never thrown — a bad context must not fail a turn.
     */
    private fun parseEntities(entityContextJson: String): List<EntityBinding> {
        val array =
            runCatching { json.parseToJsonElement(entityContextJson) as? JsonArray }.getOrNull()
                ?: return emptyList()
        return array
            .asSequence()
            .mapNotNull { el ->
                val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null

                fun str(vararg keys: String): String =
                    keys.firstNotNullOfOrNull { obj[it]?.jsonPrimitive?.contentOrNull } ?: ""
                val type = str("entity_type", "entityType")
                if (type.isEmpty()) return@mapNotNull null
                EntityBinding
                    .newBuilder()
                    .setEntityType(type)
                    .setEntityId(str("entity_id", "entityId"))
                    .setDisplayLabel(str("display_label", "displayLabel"))
                    .setConfidence(
                        keysDouble(obj, "confidence") ?: 0.0,
                    ).setSource(str("source").ifEmpty { "carryover" })
                    .build()
            }.take(MAX_ENTITIES)
            .toList()
    }

    private fun keysDouble(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
    ): Double? = obj[key]?.jsonPrimitive?.doubleOrNull

    /** Truncate to at most [maxBytes] UTF-8 bytes without splitting a char. */
    private fun truncateUtf8(
        s: String,
        maxBytes: Int,
    ): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        // Don't cut mid multi-byte sequence: back up over continuation bytes (0b10xxxxxx).
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
