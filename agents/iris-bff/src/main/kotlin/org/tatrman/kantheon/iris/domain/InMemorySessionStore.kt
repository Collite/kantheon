package org.tatrman.kantheon.iris.domain

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [SessionStore] — the behavioural reference used by
 * unit/component tests and by local boot without a database. Holds the same
 * invariants the Postgres binding must (monotone seq, snapshot-before-discard).
 *
 * `clock` and `ids` are injectable for deterministic tests.
 */
class InMemorySessionStore(
    private val clock: () -> Instant = Instant::now,
    private val ids: () -> UUID = UUID::randomUUID,
) : SessionStore {
    private val sessions = ConcurrentHashMap<UUID, SessionRecord>()
    private val turns = ConcurrentHashMap<UUID, MutableList<TurnRecord>>()
    private val snapshots = ConcurrentHashMap<UUID, MutableList<SnapshotRecord>>()
    private val v2Threads = ConcurrentHashMap<UUID, String>()
    private val lock = Any()

    override fun createSession(
        userId: String,
        tenantId: String,
    ): SessionRecord {
        val now = clock()
        val session =
            SessionRecord(
                sessionId = ids(),
                userId = userId,
                tenantId = tenantId,
                createdAt = now,
                updatedAt = now,
            )
        sessions[session.sessionId] = session
        turns[session.sessionId] = mutableListOf()
        snapshots[session.sessionId] = mutableListOf()
        return session
    }

    override fun getSession(sessionId: UUID): SessionRecord? = sessions[sessionId]

    override fun getSessionWithTurns(sessionId: UUID): SessionWithTurns? {
        val session = sessions[sessionId] ?: return null
        return SessionWithTurns(session, getTurns(sessionId))
    }

    override fun listSessions(userId: String): List<SessionSummary> =
        sessions.values
            .filter { it.userId == userId }
            .sortedByDescending { it.updatedAt }
            .map { s ->
                val visible = visibleTurns(s.sessionId)
                SessionSummary(
                    sessionId = s.sessionId,
                    title = visible.firstOrNull()?.question?.take(120) ?: "New session",
                    turnCount = visible.size,
                    updatedAt = s.updatedAt,
                )
            }

    override fun getTurns(
        sessionId: UUID,
        includeDiscarded: Boolean,
    ): List<TurnRecord> {
        val all = turns[sessionId] ?: return emptyList()
        return synchronized(lock) {
            all
                .asSequence()
                .filter { includeDiscarded || it.status != TurnStatus.DISCARDED }
                .sortedBy { it.seq }
                .toList()
        }
    }

    override fun getTurn(turnId: UUID): TurnRecord? =
        synchronized(lock) {
            turns.values
                .asSequence()
                .flatten()
                .firstOrNull { it.turnId == turnId }
        }

    override fun appendTurn(turn: NewTurn): TurnRecord =
        synchronized(lock) {
            val list = turns[turn.sessionId] ?: error("session ${turn.sessionId} not found")
            val nextSeq = (list.maxOfOrNull { it.seq } ?: 0) + 1
            val record =
                TurnRecord(
                    turnId = turn.turnId,
                    sessionId = turn.sessionId,
                    seq = nextSeq,
                    agentId = turn.agentId,
                    question = turn.question,
                    status = turn.status,
                    origin = turn.origin,
                    originRef = turn.originRef,
                    artifactRef = turn.artifactRef,
                    envelopeJson = turn.envelopeJson,
                    displayedBlockIds = turn.displayedBlockIds,
                    alternatesOffered = turn.alternatesOffered,
                    pendingResumeToken = turn.pendingResumeToken,
                    resumeIssuerAgentId = turn.resumeIssuerAgentId,
                    createdAt = clock(),
                )
            list.add(record)
            touch(turn.sessionId)
            record
        }

    override fun reset(sessionId: UUID): SessionRecord =
        synchronized(lock) {
            val session = sessions[sessionId] ?: error("session $sessionId not found")
            snapshot(session, reason = "reset")
            discardAll(sessionId)
            val cleared =
                session.copy(
                    entityContextJson = "[]",
                    currentDisplayJson = "{}",
                    updatedAt = clock(),
                )
            sessions[sessionId] = cleared
            cleared
        }

    override fun discardTurnsAfter(
        sessionId: UUID,
        fromTurnId: UUID,
    ): List<TurnRecord> =
        synchronized(lock) {
            val session = sessions[sessionId] ?: error("session $sessionId not found")
            val list = turns[sessionId] ?: return emptyList()
            val from = list.firstOrNull { it.turnId == fromTurnId } ?: return emptyList()
            snapshot(session, reason = "edit_resend")
            val discarded = mutableListOf<TurnRecord>()
            list.replaceAll { t ->
                if (t.seq > from.seq && t.status != TurnStatus.DISCARDED) {
                    val flipped = t.copy(status = TurnStatus.DISCARDED)
                    discarded.add(flipped)
                    flipped
                } else {
                    t
                }
            }
            if (discarded.isNotEmpty()) touch(sessionId)
            discarded
        }

    override fun clearPendingResumeToken(
        sessionId: UUID,
        resumeToken: String,
    ) {
        synchronized(lock) {
            turns[sessionId]?.replaceAll { t ->
                if (t.pendingResumeToken == resumeToken) t.copy(pendingResumeToken = null) else t
            }
        }
    }

    override fun restoreLatestSnapshot(sessionId: UUID): SessionRecord? =
        synchronized(lock) {
            val session = sessions[sessionId] ?: return@synchronized null
            val snap = snapshots[sessionId]?.removeLastOrNull() ?: return@synchronized null
            val keep = snap.turnIds.toSet()
            turns[sessionId]?.replaceAll { t ->
                val shouldBeVisible = t.turnId in keep
                when {
                    shouldBeVisible && t.status == TurnStatus.DISCARDED -> t.copy(status = TurnStatus.DONE)
                    !shouldBeVisible && t.status != TurnStatus.DISCARDED -> t.copy(status = TurnStatus.DISCARDED)
                    else -> t
                }
            }
            val restored = session.copy(entityContextJson = snap.entityContextJson, updatedAt = clock())
            sessions[sessionId] = restored
            restored
        }

    override fun setCurrentDisplay(
        sessionId: UUID,
        currentDisplayJson: String,
    ): SessionRecord? =
        synchronized(lock) {
            val session = sessions[sessionId] ?: return@synchronized null
            val updated = session.copy(currentDisplayJson = currentDisplayJson, updatedAt = clock())
            sessions[sessionId] = updated
            updated
        }

    override fun setEntityContext(
        sessionId: UUID,
        entityContextJson: String,
    ): SessionRecord? =
        synchronized(lock) {
            val session = sessions[sessionId] ?: return@synchronized null
            val updated = session.copy(entityContextJson = entityContextJson, updatedAt = clock())
            sessions[sessionId] = updated
            updated
        }

    override fun snapshots(sessionId: UUID): List<SnapshotRecord> = snapshots[sessionId]?.toList() ?: emptyList()

    override fun getV2Thread(sessionId: UUID): String? = v2Threads[sessionId]

    override fun putV2Thread(
        sessionId: UUID,
        v2ThreadId: String,
    ) {
        v2Threads[sessionId] = v2ThreadId
    }

    // --- internals (callers already hold `lock`) ---

    private fun visibleTurns(sessionId: UUID): List<TurnRecord> =
        (turns[sessionId] ?: emptyList())
            .filter { it.status != TurnStatus.DISCARDED }
            .sortedBy { it.seq }

    private fun snapshot(
        session: SessionRecord,
        reason: String,
    ) {
        val visibleIds = visibleTurns(session.sessionId).map { it.turnId }
        val snap =
            SnapshotRecord(
                snapshotId = ids(),
                sessionId = session.sessionId,
                reason = reason,
                entityContextJson = session.entityContextJson,
                turnIds = visibleIds,
                createdAt = clock(),
            )
        snapshots.getOrPut(session.sessionId) { mutableListOf() }.add(snap)
    }

    private fun discardAll(sessionId: UUID) {
        turns[sessionId]?.replaceAll { t ->
            if (t.status != TurnStatus.DISCARDED) t.copy(status = TurnStatus.DISCARDED) else t
        }
    }

    private fun touch(sessionId: UUID) {
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(updatedAt = clock()) }
    }
}
