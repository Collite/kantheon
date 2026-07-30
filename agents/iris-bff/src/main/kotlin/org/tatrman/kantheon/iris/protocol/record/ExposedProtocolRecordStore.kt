@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.iris.protocol.record

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.infra.IrisProtocolRecords
import org.tatrman.kantheon.iris.infra.IrisTurns
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
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
 * Postgres-backed [ProtocolRecordStore] (Exposed DSL). Mirrors the behavioural
 * invariants of [InMemoryProtocolRecordStore]: upsert by turn, `seq` ordering,
 * discarded turns excluded, [SchemaVersion.CURRENT] stamped on write.
 *
 * Upsert is update-then-insert, the house idiom (see
 * `ExposedFeedbackStore.upsertVerdict`) — non-atomic, and acceptable for the
 * same reason: the two writers for one turn are sequential within a turn's
 * lifecycle, not concurrent. Real-PG fidelity belongs to the integration tier
 * (planning-conventions §4); the unit gate runs against the fake.
 *
 * Exposed 1.0 `uuid()` columns are `kotlin.uuid.Uuid`; the domain speaks
 * `java.util.UUID`, so ids convert at this boundary.
 */
class ExposedProtocolRecordStore(
    private val db: DatabaseConnection,
    private val clock: () -> Instant = Instant::now,
) : ProtocolRecordStore {
    private fun UUID.k(): Uuid = toKotlinUuid()

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    override fun write(record: ProtocolRecord) {
        val stamped = SchemaVersion.stamp(record)
        val turn = UUID.fromString(stamped.turnId).k()
        val pointersJson = ProtocolRecordJson.pointersToJson(stamped.pointers)
        val capturesJson = ProtocolRecordJson.capturesToJson(stamped.captures)

        db.query {
            val updated =
                IrisProtocolRecords.update({ IrisProtocolRecords.turnId eq turn }) {
                    it[pointers] = pointersJson
                    it[captures] = capturesJson
                    it[schemaVersion] = stamped.schemaVersion
                }
            if (updated == 0) {
                IrisProtocolRecords.insert {
                    it[turnId] = turn
                    it[pointers] = pointersJson
                    it[captures] = capturesJson
                    it[schemaVersion] = stamped.schemaVersion
                    it[createdAt] = clock().odt()
                }
            }
        }
    }

    override fun readByTurnId(turnId: UUID): ProtocolRecord? =
        db.query {
            IrisProtocolRecords
                .selectAll()
                .where { IrisProtocolRecords.turnId eq turnId.k() }
                .singleOrNull()
                ?.toRecord()
        }

    override fun readForSession(
        sessionId: UUID,
        lastN: Int?,
    ): List<ProtocolRecord> =
        db.query {
            // Order in SQL, and for lastN take the tail by ordering DESC + LIMIT
            // rather than fetching the whole history and slicing in memory — a
            // long-lived session's record set is unbounded.
            val order = if (lastN == null) SortOrder.ASC else SortOrder.DESC
            val rows =
                (IrisProtocolRecords innerJoin IrisTurns)
                    .selectAll()
                    .where {
                        (IrisTurns.sessionId eq sessionId.k()) and
                            (IrisTurns.status neq TurnStatus.DISCARDED.wire)
                    }.orderBy(IrisTurns.seq to order)
                    .let { if (lastN == null) it else it.limit(lastN.coerceAtLeast(0)) }
                    .map { it.toRecord() }
            if (lastN == null) rows else rows.reversed()
        }

    private fun ResultRow.toRecord(): ProtocolRecord =
        ProtocolRecord
            .newBuilder()
            .setTurnId(this[IrisProtocolRecords.turnId].toJavaUuid().toString())
            .setPointers(ProtocolRecordJson.pointersFromJson(this[IrisProtocolRecords.pointers]))
            .setCaptures(ProtocolRecordJson.capturesFromJson(this[IrisProtocolRecords.captures]))
            .setSchemaVersion(this[IrisProtocolRecords.schemaVersion])
            .build()
}
