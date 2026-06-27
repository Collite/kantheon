package org.tatrman.kantheon.kleio.persistence

/**
 * One `kleio_turns` row per turn (contracts §3). Conversation MEMORY is Iris's
 * job (the Golem rule) — Kleio persists a single turn record (envelopes +
 * sources_used + resource_usage as JSON blobs), not a thread.
 */
data class KleioTurnRecord(
    val turnId: String,
    val sessionId: String,
    val notebookId: String,
    val question: String,
    val status: String,
    val envelopesJson: String,
    val sourcesUsedJson: String,
    val resourceUsageJson: String,
    val createdAt: String = "",
)

interface KleioTurnsRepository {
    fun save(record: KleioTurnRecord)

    fun get(turnId: String): KleioTurnRecord?
}

/** In-memory turns (running default; the Exposed/PG store is the deploy path). */
class InMemoryKleioTurnsRepository : KleioTurnsRepository {
    private val byTurn = linkedMapOf<String, KleioTurnRecord>()

    override fun save(record: KleioTurnRecord) {
        byTurn[record.turnId] = record
    }

    override fun get(turnId: String): KleioTurnRecord? = byTurn[turnId]
}
