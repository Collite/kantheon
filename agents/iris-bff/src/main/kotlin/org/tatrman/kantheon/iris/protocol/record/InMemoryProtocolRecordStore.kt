package org.tatrman.kantheon.iris.protocol.record

import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [ProtocolRecordStore] — the behavioural reference for
 * the unit/component gate and for local boot without a database. Holds the same
 * invariants the Postgres binding must: upsert-by-turn, `seq` ordering,
 * discarded turns excluded, [SchemaVersion.CURRENT] stamped on every write.
 *
 * `readForSession` needs the session/seq/visibility of each turn, which in
 * Postgres comes from the `iris_turns` join (there is no `session_id` column on
 * `iris_protocol_records` — the FK *is* the join). [linkTurn] stands in for
 * those rows: tests declare the turn index the same way the real schema
 * provides it. A record written for an unlinked turn is stored and readable by
 * id, but belongs to no session — exactly what the SQL join does.
 */
class InMemoryProtocolRecordStore : ProtocolRecordStore {
    private data class TurnRef(
        val sessionId: UUID,
        val seq: Int,
        val discarded: Boolean,
    )

    private val records = ConcurrentHashMap<UUID, ProtocolRecord>()
    private val turnIndex = ConcurrentHashMap<UUID, TurnRef>()

    /** Declare the `iris_turns` row this fake joins against. */
    fun linkTurn(
        turnId: UUID,
        sessionId: UUID,
        seq: Int,
        discarded: Boolean = false,
    ) {
        turnIndex[turnId] = TurnRef(sessionId, seq, discarded)
    }

    override fun write(record: ProtocolRecord) {
        val stamped = SchemaVersion.stamp(record)
        records[UUID.fromString(stamped.turnId)] = stamped
    }

    override fun readByTurnId(turnId: UUID): ProtocolRecord? = records[turnId]

    override fun readForSession(
        sessionId: UUID,
        lastN: Int?,
    ): List<ProtocolRecord> {
        val ordered =
            records.values
                .mapNotNull { record ->
                    val ref = turnIndex[UUID.fromString(record.turnId)] ?: return@mapNotNull null
                    if (ref.sessionId != sessionId || ref.discarded) null else ref.seq to record
                }.sortedBy { it.first }
                .map { it.second }
        return if (lastN == null) ordered else ordered.takeLast(lastN.coerceAtLeast(0))
    }
}
