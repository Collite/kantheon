package org.tatrman.kantheon.golem.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [TurnsRepository] — the unit/component-test fake and the local-boot
 * store when `golem.db.enabled = false`. Insertion-ordered so `findByRequestId`
 * can return the latest matching row deterministically.
 */
class InMemoryTurnsRepository : TurnsRepository {
    private val byId = ConcurrentHashMap<UUID, GolemTurnRecord>()

    @Synchronized
    override fun insert(turn: GolemTurnRecord) {
        if (byId.putIfAbsent(turn.id, turn) != null) throw DuplicateTurnException(turn.id)
    }

    override fun findById(id: UUID): GolemTurnRecord? = byId[id]

    @Synchronized
    override fun findByRequestId(requestId: UUID): GolemTurnRecord? =
        byId.values
            .filter { it.requestId == requestId }
            // Deterministic latest-wins: createdAt then id (createdAt is second-granular
            // + caller-supplied, so a tiebreak is needed — carry-in, Stage 2.4 T0).
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    override fun findByBubbleId(
        bubbleId: String,
        userId: String,
        tenantId: String,
    ): GolemTurnRecord? =
        byId.values
            .filter { it.userId == userId && it.tenantId == tenantId && bubbleIdOf(it) == bubbleId }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    private fun bubbleIdOf(turn: GolemTurnRecord): String? =
        turn.currentViewJson?.let {
            runCatching {
                json
                    .parseToJsonElement(it)
                    .jsonObject["bubbleId"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
