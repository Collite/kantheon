@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.iris.infra

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.iris.domain.FeedbackRecord
import org.tatrman.kantheon.iris.domain.FeedbackStore
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
 * Postgres-backed [FeedbackStore] (Exposed). Upsert on `(turn_id, user_id)`:
 * update-then-insert (non-atomic; real-PG fidelity is integration-deferred per
 * planning-conventions §4 — the in-memory fake backs the unit/component gate).
 */
class ExposedFeedbackStore(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
    private val ids: () -> UUID = UUID::randomUUID,
) : FeedbackStore {
    private fun UUID.k(): Uuid = toKotlinUuid()

    private fun Uuid.j(): UUID = toJavaUuid()

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    override fun upsertCorrectedAgent(
        turnId: UUID,
        userId: String,
        agentId: String,
        correctedAgentId: String,
    ) {
        db.query {
            val updated =
                IrisFeedback.update({
                    (IrisFeedback.turnId eq turnId.k()) and (IrisFeedback.userId eq userId)
                }) {
                    it[verdict] = "down"
                    it[reason] = "wrong_agent"
                    it[IrisFeedback.correctedAgentId] = correctedAgentId
                }
            if (updated == 0) {
                IrisFeedback.insert {
                    it[feedbackId] = ids().k()
                    it[IrisFeedback.turnId] = turnId.k()
                    it[IrisFeedback.userId] = userId
                    it[IrisFeedback.agentId] = agentId
                    it[verdict] = "down"
                    it[reason] = "wrong_agent"
                    it[IrisFeedback.correctedAgentId] = correctedAgentId
                    it[createdAt] = clock().odt()
                }
            }
        }
    }

    override fun upsertVerdict(
        turnId: UUID,
        userId: String,
        agentId: String,
        verdict: String,
        reason: String?,
        comment: String?,
    ): FeedbackRecord {
        db.query {
            val updated =
                IrisFeedback.update({
                    (IrisFeedback.turnId eq turnId.k()) and (IrisFeedback.userId eq userId)
                }) {
                    it[IrisFeedback.agentId] = agentId
                    it[IrisFeedback.verdict] = verdict
                    it[IrisFeedback.reason] = reason
                    it[IrisFeedback.comment] = comment
                }
            if (updated == 0) {
                IrisFeedback.insert {
                    it[feedbackId] = ids().k()
                    it[IrisFeedback.turnId] = turnId.k()
                    it[IrisFeedback.userId] = userId
                    it[IrisFeedback.agentId] = agentId
                    it[IrisFeedback.verdict] = verdict
                    it[IrisFeedback.reason] = reason
                    it[IrisFeedback.comment] = comment
                    it[createdAt] = clock().odt()
                }
            }
        }
        return get(turnId, userId)!!
    }

    override fun get(
        turnId: UUID,
        userId: String,
    ): FeedbackRecord? =
        db.query {
            IrisFeedback
                .selectAll()
                .where { (IrisFeedback.turnId eq turnId.k()) and (IrisFeedback.userId eq userId) }
                .singleOrNull()
                ?.toRecord()
        }

    override fun all(): List<FeedbackRecord> = db.query { IrisFeedback.selectAll().map { it.toRecord() } }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRecord(): FeedbackRecord =
        FeedbackRecord(
            turnId = this[IrisFeedback.turnId].j(),
            userId = this[IrisFeedback.userId],
            agentId = this[IrisFeedback.agentId],
            verdict = this[IrisFeedback.verdict],
            reason = this[IrisFeedback.reason],
            comment = this[IrisFeedback.comment],
            correctedAgentId = this[IrisFeedback.correctedAgentId],
        )
}
