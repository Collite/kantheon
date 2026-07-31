package org.tatrman.kantheon.iris.protocol.record

import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import java.util.UUID

/**
 * Persistence for the per-turn protocol record (contracts §6.1) — the write
 * path's only sink and the read path's spine. [InMemoryProtocolRecordStore] is
 * the behavioural reference used by the unit/component gate;
 * `ExposedProtocolRecordStore` is the Postgres binding (real-PG fidelity is
 * exercised in the integration tier, planning-conventions §4).
 *
 * **Not owner-aware, by design** — same discipline as
 * [org.tatrman.kantheon.iris.domain.SessionStore]: the route layer is the trust
 * boundary and rejects a non-member before any id-keyed call lands here (PT-3
 * authorizes any session member). Do not call these with an unverified id.
 *
 * Every write stamps [SchemaVersion.CURRENT]; the caller's opinion of the
 * version is overwritten, not trusted.
 */
interface ProtocolRecordStore {
    /**
     * Upsert by `turn_id` — last write wins. Idempotent because the capture path
     * may legitimately write twice for one turn: once when the agent response
     * lands, once when a late source (F7, a retried dispatch) fills a gap. The
     * second write carries the fuller record, so overwriting is the correct
     * merge, not a lost update.
     */
    fun write(record: ProtocolRecord)

    fun readByTurnId(turnId: UUID): ProtocolRecord?

    /**
     * Records for a session's **visible** turns, ordered by `iris_turns.seq`
     * ascending (oldest → newest — the order a protocol document reads in).
     *
     * Discarded turns are excluded: after a reset or an edit-resend the
     * conversation no longer contains them, and a protocol that narrated turns
     * the user cannot see would be actively misleading. Their rows survive (the
     * turn row does), they are simply not part of any scope.
     *
     * [lastN], when set, takes the *most recent* N turns and still returns them
     * oldest → newest. Turns with no record yet — a turn still in flight, or one
     * that predates this table — are absent from the result; the assembler
     * reports the gap in the receipts rather than failing the document.
     */
    fun readForSession(
        sessionId: UUID,
        lastN: Int? = null,
    ): List<ProtocolRecord>
}
