@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.iris.infra

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.SessionRecord
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.domain.SessionSummary
import org.tatrman.kantheon.iris.domain.SessionWithTurns
import org.tatrman.kantheon.iris.domain.SnapshotRecord
import org.tatrman.kantheon.iris.domain.TurnOriginKind
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Postgres-backed [SessionStore] (Exposed DSL). Mirrors the behavioural
 * invariants of [org.tatrman.kantheon.iris.domain.InMemorySessionStore]:
 * monotone `seq`, snapshot-before-discard. Real-PG fidelity is exercised in the
 * integration-test suite (planning-conventions §4) — the unit/component gate
 * runs against the in-memory fake.
 *
 * Exposed 1.0 `uuid()` columns are `kotlin.uuid.Uuid`; the domain uses
 * `java.util.UUID`, so ids are converted at this boundary.
 */
class ExposedSessionStore(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
    private val ids: () -> UUID = UUID::randomUUID,
) : SessionStore {
    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private fun UUID.k(): Uuid = toKotlinUuid()

    private fun Uuid.j(): UUID = toJavaUuid()

    override fun createSession(
        userId: String,
        tenantId: String,
    ): SessionRecord =
        db.query {
            val now = clock()
            val id = ids()
            IrisSessions.insert {
                it[sessionId] = id.k()
                it[IrisSessions.userId] = userId
                it[IrisSessions.tenantId] = tenantId
                it[entityContext] = "[]"
                it[currentDisplay] = "{}"
                it[createdAt] = now.odt()
                it[updatedAt] = now.odt()
            }
            SessionRecord(id, userId, tenantId, "[]", "{}", now, now)
        }

    override fun getSession(sessionId: UUID): SessionRecord? =
        db.query {
            IrisSessions
                .selectAll()
                .where { IrisSessions.sessionId eq sessionId.k() }
                .singleOrNull()
                ?.toSession()
        }

    override fun getSessionWithTurns(sessionId: UUID): SessionWithTurns? =
        db.query {
            val session =
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .singleOrNull()
                    ?.toSession() ?: return@query null
            SessionWithTurns(session, loadTurns(sessionId, includeDiscarded = false))
        }

    override fun listSessions(userId: String): List<SessionSummary> =
        db.query {
            IrisSessions
                .selectAll()
                .where { IrisSessions.userId eq userId }
                .orderBy(IrisSessions.updatedAt to SortOrder.DESC)
                .map { row ->
                    val sessionId = row[IrisSessions.sessionId].j()
                    val visible = loadTurns(sessionId, includeDiscarded = false)
                    SessionSummary(
                        sessionId = sessionId,
                        title = visible.firstOrNull()?.question?.take(120) ?: "New session",
                        turnCount = visible.size,
                        updatedAt = row[IrisSessions.updatedAt].toInstant(),
                    )
                }
        }

    override fun getTurns(
        sessionId: UUID,
        includeDiscarded: Boolean,
    ): List<TurnRecord> = db.query { loadTurns(sessionId, includeDiscarded) }

    override fun getTurn(turnId: UUID): TurnRecord? =
        db.query {
            IrisTurns
                .selectAll()
                .where { IrisTurns.turnId eq turnId.k() }
                .singleOrNull()
                ?.toTurn()
        }

    override fun appendTurn(turn: NewTurn): TurnRecord =
        db.query {
            // Lock the session row FOR UPDATE before reading MAX(seq): under
            // REPEATABLE READ two concurrent appends would otherwise both read
            // the same max and collide on UNIQUE(session_id, seq). Serialising on
            // the session row matches InMemorySessionStore's `synchronized` append.
            IrisSessions
                .selectAll()
                .where { IrisSessions.sessionId eq turn.sessionId.k() }
                .forUpdate()
                .firstOrNull()
                ?: error("session ${turn.sessionId} not found")
            val nextSeq =
                (
                    IrisTurns
                        .selectAll()
                        .where { IrisTurns.sessionId eq turn.sessionId.k() }
                        .maxOfOrNull { it[IrisTurns.seq] } ?: 0
                ) + 1
            val id = turn.turnId
            val now = clock()
            IrisTurns.insert {
                it[turnId] = id.k()
                it[sessionId] = turn.sessionId.k()
                it[seq] = nextSeq
                it[agentId] = turn.agentId
                it[question] = turn.question
                it[status] = turn.status.wire
                it[origin] = turn.origin.wire
                it[originRef] = turn.originRef
                it[artifactRef] = turn.artifactRef
                it[envelopeJson] = turn.envelopeJson
                it[displayedBlockIds] = turn.displayedBlockIds
                it[alternatesOffered] = turn.alternatesOffered
                it[pendingResumeToken] = turn.pendingResumeToken
                it[resumeIssuerAgentId] = turn.resumeIssuerAgentId
                it[createdAt] = now.odt()
            }
            touch(turn.sessionId, now)
            TurnRecord(
                turnId = id,
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
                createdAt = now,
            )
        }

    override fun reset(sessionId: UUID): SessionRecord =
        db.query {
            val session =
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .singleOrNull()
                    ?.toSession() ?: error("session $sessionId not found")
            writeSnapshot(session, reason = "reset")
            IrisTurns.update({
                (IrisTurns.sessionId eq sessionId.k()) and
                    (IrisTurns.status neq TurnStatus.DISCARDED.wire)
            }) {
                it[status] = TurnStatus.DISCARDED.wire
            }
            val now = clock()
            IrisSessions.update({ IrisSessions.sessionId eq sessionId.k() }) {
                it[entityContext] = "[]"
                it[currentDisplay] = "{}"
                it[updatedAt] = now.odt()
            }
            session.copy(entityContextJson = "[]", currentDisplayJson = "{}", updatedAt = now)
        }

    override fun discardTurnsAfter(
        sessionId: UUID,
        fromTurnId: UUID,
    ): List<TurnRecord> =
        db.query {
            val from =
                IrisTurns
                    .selectAll()
                    .where { (IrisTurns.sessionId eq sessionId.k()) and (IrisTurns.turnId eq fromTurnId.k()) }
                    .singleOrNull()
                    ?.toTurn() ?: return@query emptyList()
            val session =
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .singleOrNull()
                    ?.toSession() ?: error("session $sessionId not found")
            writeSnapshot(session, reason = "edit_resend")
            val toDiscard =
                IrisTurns
                    .selectAll()
                    .where {
                        (IrisTurns.sessionId eq sessionId.k()) and
                            (IrisTurns.seq greater from.seq) and
                            (IrisTurns.status neq TurnStatus.DISCARDED.wire)
                    }.map { it.toTurn() }
            if (toDiscard.isNotEmpty()) {
                IrisTurns.update({
                    (IrisTurns.sessionId eq sessionId.k()) and
                        (IrisTurns.seq greater from.seq) and
                        (IrisTurns.status neq TurnStatus.DISCARDED.wire)
                }) {
                    it[status] = TurnStatus.DISCARDED.wire
                }
                touch(sessionId, clock())
            }
            toDiscard.map { it.copy(status = TurnStatus.DISCARDED) }
        }

    override fun restoreLatestSnapshot(sessionId: UUID): SessionRecord? =
        db.query {
            val session =
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .singleOrNull()
                    ?.toSession() ?: return@query null
            val snap =
                IrisSnapshots
                    .selectAll()
                    .where { IrisSnapshots.sessionId eq sessionId.k() }
                    .orderBy(IrisSnapshots.createdAt to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.toSnapshot() ?: return@query null
            val keep = snap.turnIds.map { it.k() }
            // Restore the captured turns to visible (DONE); discard everything else.
            // Exposed renders inList(empty)→FALSE and notInList(empty)→TRUE, which is
            // exactly right for an empty snapshot (restore nothing, discard all).
            if (keep.isNotEmpty()) {
                IrisTurns.update({
                    (IrisTurns.sessionId eq sessionId.k()) and
                        (IrisTurns.turnId inList keep) and
                        (IrisTurns.status eq TurnStatus.DISCARDED.wire)
                }) {
                    it[status] = TurnStatus.DONE.wire
                }
            }
            IrisTurns.update({
                (IrisTurns.sessionId eq sessionId.k()) and
                    (IrisTurns.turnId notInList keep) and
                    (IrisTurns.status neq TurnStatus.DISCARDED.wire)
            }) {
                it[status] = TurnStatus.DISCARDED.wire
            }
            // Consume the snapshot so repeated undo walks further back.
            IrisSnapshots.deleteWhere { IrisSnapshots.snapshotId eq snap.snapshotId.k() }
            val now = clock()
            IrisSessions.update({ IrisSessions.sessionId eq sessionId.k() }) {
                it[entityContext] = snap.entityContextJson
                it[updatedAt] = now.odt()
            }
            session.copy(entityContextJson = snap.entityContextJson, updatedAt = now)
        }

    override fun snapshots(sessionId: UUID): List<SnapshotRecord> =
        db.query {
            IrisSnapshots
                .selectAll()
                .where { IrisSnapshots.sessionId eq sessionId.k() }
                .orderBy(IrisSnapshots.createdAt to SortOrder.ASC)
                .map { it.toSnapshot() }
        }

    override fun setCurrentDisplay(
        sessionId: UUID,
        currentDisplayJson: String,
    ): SessionRecord? =
        db.query {
            val now = clock()
            val updated =
                IrisSessions.update({ IrisSessions.sessionId eq sessionId.k() }) {
                    it[currentDisplay] = currentDisplayJson
                    it[updatedAt] = now.odt()
                }
            if (updated == 0) {
                null
            } else {
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .single()
                    .toSession()
            }
        }

    override fun setEntityContext(
        sessionId: UUID,
        entityContextJson: String,
    ): SessionRecord? =
        db.query {
            val now = clock()
            val updated =
                IrisSessions.update({ IrisSessions.sessionId eq sessionId.k() }) {
                    it[entityContext] = entityContextJson
                    it[updatedAt] = now.odt()
                }
            if (updated == 0) {
                null
            } else {
                IrisSessions
                    .selectAll()
                    .where { IrisSessions.sessionId eq sessionId.k() }
                    .single()
                    .toSession()
            }
        }

    override fun getV2Thread(sessionId: UUID): String? =
        db.query {
            IrisV2Threads
                .selectAll()
                .where { IrisV2Threads.sessionId eq sessionId.k() }
                .singleOrNull()
                ?.get(IrisV2Threads.v2ThreadId)
        }

    override fun putV2Thread(
        sessionId: UUID,
        v2ThreadId: String,
    ) {
        // Idempotent set (insert-once): the session ↔ thread binding is stable, so
        // a re-put is a no-op rather than a PK violation — matching the in-memory
        // store's map-overwrite semantics.
        db.query {
            IrisV2Threads.insertIgnore {
                it[IrisV2Threads.sessionId] = sessionId.k()
                it[IrisV2Threads.v2ThreadId] = v2ThreadId
            }
        }
    }

    override fun clearPendingResumeToken(
        sessionId: UUID,
        resumeToken: String,
    ) {
        db.query {
            IrisTurns.update({
                (IrisTurns.sessionId eq sessionId.k()) and
                    (IrisTurns.pendingResumeToken eq resumeToken)
            }) {
                it[pendingResumeToken] = null
            }
        }
    }

    // --- internals (run inside a db.query transaction) ---

    private fun loadTurns(
        sessionId: UUID,
        includeDiscarded: Boolean,
    ): List<TurnRecord> =
        IrisTurns
            .selectAll()
            .where {
                val base = IrisTurns.sessionId eq sessionId.k()
                // filter discarded in SQL (not post-fetch) so a long history of
                // discarded turns isn't dragged into memory on every read.
                if (includeDiscarded) base else base and (IrisTurns.status neq TurnStatus.DISCARDED.wire)
            }.orderBy(IrisTurns.seq to SortOrder.ASC)
            .map { it.toTurn() }

    private fun writeSnapshot(
        session: SessionRecord,
        reason: String,
    ) {
        val visibleIds = loadTurns(session.sessionId, includeDiscarded = false).map { it.turnId.k() }
        IrisSnapshots.insert {
            it[snapshotId] = ids().k()
            it[sessionId] = session.sessionId.k()
            it[IrisSnapshots.reason] = reason
            it[entityContext] = session.entityContextJson
            it[turnIds] = visibleIds
            it[createdAt] = clock().odt()
        }
    }

    private fun touch(
        sessionId: UUID,
        now: Instant,
    ) {
        IrisSessions.update({ IrisSessions.sessionId eq sessionId.k() }) {
            it[updatedAt] = now.odt()
        }
    }

    private fun ResultRow.toSession() =
        SessionRecord(
            sessionId = this[IrisSessions.sessionId].j(),
            userId = this[IrisSessions.userId],
            tenantId = this[IrisSessions.tenantId],
            entityContextJson = this[IrisSessions.entityContext],
            currentDisplayJson = this[IrisSessions.currentDisplay],
            createdAt = this[IrisSessions.createdAt].toInstant(),
            updatedAt = this[IrisSessions.updatedAt].toInstant(),
        )

    private fun ResultRow.toTurn() =
        TurnRecord(
            turnId = this[IrisTurns.turnId].j(),
            sessionId = this[IrisTurns.sessionId].j(),
            seq = this[IrisTurns.seq],
            agentId = this[IrisTurns.agentId],
            question = this[IrisTurns.question],
            status = TurnStatus.fromWire(this[IrisTurns.status]),
            origin = TurnOriginKind.fromWire(this[IrisTurns.origin]),
            originRef = this[IrisTurns.originRef],
            artifactRef = this[IrisTurns.artifactRef],
            envelopeJson = this[IrisTurns.envelopeJson],
            displayedBlockIds = this[IrisTurns.displayedBlockIds],
            alternatesOffered = this[IrisTurns.alternatesOffered],
            pendingResumeToken = this[IrisTurns.pendingResumeToken],
            resumeIssuerAgentId = this[IrisTurns.resumeIssuerAgentId],
            createdAt = this[IrisTurns.createdAt].toInstant(),
        )

    private fun ResultRow.toSnapshot() =
        SnapshotRecord(
            snapshotId = this[IrisSnapshots.snapshotId].j(),
            sessionId = this[IrisSnapshots.sessionId].j(),
            reason = this[IrisSnapshots.reason],
            entityContextJson = this[IrisSnapshots.entityContext],
            turnIds = this[IrisSnapshots.turnIds].map { it.j() },
            createdAt = this[IrisSnapshots.createdAt].toInstant(),
        )
}
