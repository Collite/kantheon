@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.golem.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.extract
import shared.libs.db.common.DatabaseConnection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Postgres-backed [TurnsRepository] (Exposed DSL). Mirrors the behaviour of
 * [InMemoryTurnsRepository]; real-PG fidelity is exercised in the integration
 * suite (planning-conventions §4) — the unit/component gate runs the fake.
 */
class ExposedTurnsRepository(
    private val db: DatabaseConnection,
) : TurnsRepository {
    private fun UUID.k(): Uuid = toKotlinUuid()

    private fun Uuid.j(): UUID = toJavaUuid()

    private fun java.time.Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    override fun insert(turn: GolemTurnRecord) {
        try {
            doInsert(turn)
        } catch (e: Exception) {
            // Normalise a Postgres unique-violation (SQLState 23505) to DuplicateTurnException,
            // matching InMemory (carry-in parity, Stage 2.4 T0). Other errors propagate.
            if (isUniqueViolation(e)) throw DuplicateTurnException(turn.id)
            throw e
        }
    }

    private fun isUniqueViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "23505" }

    private fun doInsert(turn: GolemTurnRecord) {
        db.query {
            GolemTurnsTable.insert {
                it[id] = turn.id.k()
                it[requestId] = turn.requestId.k()
                it[golemId] = turn.golemId
                it[userId] = turn.userId
                it[tenantId] = turn.tenantId
                it[question] = turn.question
                it[resolvedIntent] = turn.resolvedIntentJson
                it[plan] = turn.planJson
                it[envelopes] = turn.envelopesJson
                it[currentView] = turn.currentViewJson
                it[stepRecords] = turn.stepRecordsJson
                it[resourceUsage] = turn.resourceUsageJson
                it[pendingResumeToken] = turn.pendingResumeToken
                it[status] = turn.status.wire
                it[createdAt] = turn.createdAt.odt()
                it[finalisedAt] = turn.finalisedAt?.odt()
            }
        }
    }

    override fun findById(id: UUID): GolemTurnRecord? =
        db.query {
            GolemTurnsTable
                .selectAll()
                .where { GolemTurnsTable.id eq id.k() }
                .singleOrNull()
                ?.toRecord()
        }

    override fun findByRequestId(requestId: UUID): GolemTurnRecord? =
        db.query {
            GolemTurnsTable
                .selectAll()
                .where { GolemTurnsTable.requestId eq requestId.k() }
                // Deterministic latest-wins: createdAt then id (carry-in tiebreak, Stage 2.4 T0).
                .orderBy(GolemTurnsTable.createdAt to SortOrder.DESC, GolemTurnsTable.id to SortOrder.DESC)
                .firstOrNull()
                ?.toRecord()
        }

    override fun findByBubbleId(
        bubbleId: String,
        userId: String,
        tenantId: String,
    ): GolemTurnRecord? =
        db.query {
            GolemTurnsTable
                .selectAll()
                .where {
                    (GolemTurnsTable.currentView.extract<String>("bubbleId", toScalar = true) eq bubbleId) and
                        (GolemTurnsTable.userId eq userId) and
                        (GolemTurnsTable.tenantId eq tenantId)
                }.orderBy(GolemTurnsTable.createdAt to SortOrder.DESC, GolemTurnsTable.id to SortOrder.DESC)
                .firstOrNull()
                ?.toRecord()
        }

    private fun ResultRow.toRecord(): GolemTurnRecord =
        GolemTurnRecord(
            id = this[GolemTurnsTable.id].j(),
            requestId = this[GolemTurnsTable.requestId].j(),
            golemId = this[GolemTurnsTable.golemId],
            userId = this[GolemTurnsTable.userId],
            tenantId = this[GolemTurnsTable.tenantId],
            question = this[GolemTurnsTable.question],
            resolvedIntentJson = this[GolemTurnsTable.resolvedIntent],
            planJson = this[GolemTurnsTable.plan],
            envelopesJson = this[GolemTurnsTable.envelopes],
            currentViewJson = this[GolemTurnsTable.currentView],
            stepRecordsJson = this[GolemTurnsTable.stepRecords],
            resourceUsageJson = this[GolemTurnsTable.resourceUsage],
            pendingResumeToken = this[GolemTurnsTable.pendingResumeToken],
            status = GolemTurnStatus.fromWire(this[GolemTurnsTable.status]),
            createdAt = this[GolemTurnsTable.createdAt].toInstant(),
            finalisedAt = this[GolemTurnsTable.finalisedAt]?.toInstant(),
        )
}
