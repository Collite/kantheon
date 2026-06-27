@file:OptIn(ExperimentalUuidApi::class)

package org.tatrman.kantheon.pythia.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.extract
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private fun UUID.k(): Uuid = toKotlinUuid()

private fun Uuid.j(): UUID = toJavaUuid()

private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/**
 * Postgres-backed repositories (Exposed DSL). Mirror the in-memory fakes; real-PG
 * fidelity is exercised in the integration suite (planning-conventions §4) — the
 * unit/component gate runs the fakes.
 */
class ExposedInvestigationRepository(
    private val db: DatabaseConnection,
) : InvestigationRepository {
    override fun insert(record: InvestigationRecord) {
        try {
            db.query {
                PythiaInvestigationsTable.insert { it.fromRecord(record) }
            }
        } catch (e: Exception) {
            if (isUniqueViolation(e)) throw DuplicateInvestigationException(record.id)
            throw e
        }
    }

    override fun findById(id: UUID): InvestigationRecord? =
        db.query {
            PythiaInvestigationsTable
                .selectAll()
                .where { PythiaInvestigationsTable.id eq id.k() }
                .singleOrNull()
                ?.toRecord()
        }

    override fun save(record: InvestigationRecord) {
        db.query {
            // `status` is intentionally NOT written here — it is owned exclusively by
            // compareAndSetStatus, so a stale snapshot can never roll a transition back
            // (H2). save touches the mutable payload columns only; a missing row no-ops.
            PythiaInvestigationsTable.update({ PythiaInvestigationsTable.id eq record.id.k() }) {
                it[resolution] = record.resolutionJson
                it[plan] = record.planJson
                it[conclusion] = record.conclusionJson
                it[resourceUsage] = record.resourceUsageJson
                it[warnings] = record.warningsJson
                it[awaitingSince] = record.awaitingSince?.odt()
                it[awaitingTtlUntil] = record.awaitingTtlUntil?.odt()
                it[updatedAt] = Instant.now().odt()
                it[finalisedAt] = record.finalisedAt?.odt()
            }
        }
    }

    override fun compareAndSetStatus(
        id: UUID,
        from: String,
        to: String,
    ): Boolean =
        db.query {
            val clears = from.isAwaiting()
            PythiaInvestigationsTable.update({
                (PythiaInvestigationsTable.id eq id.k()) and (PythiaInvestigationsTable.status eq from)
            }) {
                it[status] = to
                it[updatedAt] = Instant.now().odt()
                if (clears) {
                    it[awaitingSince] = null
                    it[awaitingTtlUntil] = null
                }
            }
        } == 1

    override fun list(
        userId: String,
        statuses: Set<String>,
        page: Int,
        pageSize: Int,
    ): InvestigationPage =
        db.query {
            val userKey = PythiaInvestigationsTable.caller.extract<String>("userId", toScalar = true)
            val query =
                if (statuses.isEmpty()) {
                    PythiaInvestigationsTable.selectAll().where { userKey eq userId }
                } else {
                    PythiaInvestigationsTable.selectAll().where {
                        (userKey eq userId) and (PythiaInvestigationsTable.status inList statuses)
                    }
                }
            val rows =
                query
                    .orderBy(
                        PythiaInvestigationsTable.createdAt to SortOrder.DESC,
                        PythiaInvestigationsTable.id to SortOrder.DESC,
                    ).limit(pageSize + 1)
                    .offset(page.toLong() * pageSize)
                    .map { it.toRecord() }
            val hasMore = rows.size > pageSize
            InvestigationPage(rows.take(pageSize), if (hasMore) page + 1 else null)
        }

    override fun findExpiredAwaiting(nowEpochMillis: Long): List<InvestigationRecord> =
        db.query {
            PythiaInvestigationsTable
                .selectAll()
                .where {
                    PythiaInvestigationsTable.awaitingTtlUntil.isNotNull() and
                        (PythiaInvestigationsTable.awaitingTtlUntil less Instant.ofEpochMilli(nowEpochMillis).odt())
                }.map { it.toRecord() }
                .filter { it.status.isAwaiting() }
        }

    private fun org.jetbrains.exposed.v1.core.statements.InsertStatement<Number>.fromRecord(
        record: InvestigationRecord,
    ) {
        this[PythiaInvestigationsTable.id] = record.id.k()
        this[PythiaInvestigationsTable.parentId] = record.parentId?.k()
        this[PythiaInvestigationsTable.caller] = record.callerJson
        this[PythiaInvestigationsTable.question] = record.question
        this[PythiaInvestigationsTable.request] = record.requestJson
        this[PythiaInvestigationsTable.status] = record.status
        this[PythiaInvestigationsTable.resolution] = record.resolutionJson
        this[PythiaInvestigationsTable.plan] = record.planJson
        this[PythiaInvestigationsTable.conclusion] = record.conclusionJson
        this[PythiaInvestigationsTable.resourceUsage] = record.resourceUsageJson
        this[PythiaInvestigationsTable.warnings] = record.warningsJson
        this[PythiaInvestigationsTable.awaitingSince] = record.awaitingSince?.odt()
        this[PythiaInvestigationsTable.awaitingTtlUntil] = record.awaitingTtlUntil?.odt()
        this[PythiaInvestigationsTable.createdAt] = record.createdAt.odt()
        this[PythiaInvestigationsTable.updatedAt] = record.updatedAt.odt()
        this[PythiaInvestigationsTable.finalisedAt] = record.finalisedAt?.odt()
    }

    private fun ResultRow.toRecord(): InvestigationRecord =
        InvestigationRecord(
            id = this[PythiaInvestigationsTable.id].j(),
            parentId = this[PythiaInvestigationsTable.parentId]?.j(),
            callerJson = this[PythiaInvestigationsTable.caller],
            question = this[PythiaInvestigationsTable.question],
            requestJson = this[PythiaInvestigationsTable.request],
            status = this[PythiaInvestigationsTable.status],
            resolutionJson = this[PythiaInvestigationsTable.resolution],
            planJson = this[PythiaInvestigationsTable.plan],
            conclusionJson = this[PythiaInvestigationsTable.conclusion],
            resourceUsageJson = this[PythiaInvestigationsTable.resourceUsage],
            warningsJson = this[PythiaInvestigationsTable.warnings],
            awaitingSince = this[PythiaInvestigationsTable.awaitingSince]?.toInstant(),
            awaitingTtlUntil = this[PythiaInvestigationsTable.awaitingTtlUntil]?.toInstant(),
            createdAt = this[PythiaInvestigationsTable.createdAt].toInstant(),
            updatedAt = this[PythiaInvestigationsTable.updatedAt].toInstant(),
            finalisedAt = this[PythiaInvestigationsTable.finalisedAt]?.toInstant(),
        )
}

class ExposedHypothesisRepository(
    private val db: DatabaseConnection,
) : HypothesisRepository {
    override fun upsert(record: HypothesisRecord) {
        db.query {
            val updated =
                PythiaHypothesesTable.update({
                    (PythiaHypothesesTable.investigationId eq record.investigationId.k()) and
                        (PythiaHypothesesTable.hypId eq record.hypId)
                }) {
                    it[parentHypId] = record.parentHypId
                    it[body] = record.bodyJson
                    it[status] = record.status
                    it[confidence] = record.confidence
                }
            if (updated == 0) {
                PythiaHypothesesTable.insert {
                    it[investigationId] = record.investigationId.k()
                    it[hypId] = record.hypId
                    it[parentHypId] = record.parentHypId
                    it[body] = record.bodyJson
                    it[status] = record.status
                    it[confidence] = record.confidence
                }
            }
        }
    }

    override fun findByInvestigation(investigationId: UUID): List<HypothesisRecord> =
        db.query {
            PythiaHypothesesTable
                .selectAll()
                .where { PythiaHypothesesTable.investigationId eq investigationId.k() }
                .orderBy(PythiaHypothesesTable.hypId to SortOrder.ASC)
                .map {
                    HypothesisRecord(
                        investigationId = it[PythiaHypothesesTable.investigationId].j(),
                        hypId = it[PythiaHypothesesTable.hypId],
                        parentHypId = it[PythiaHypothesesTable.parentHypId],
                        bodyJson = it[PythiaHypothesesTable.body],
                        status = it[PythiaHypothesesTable.status],
                        confidence = it[PythiaHypothesesTable.confidence],
                    )
                }
        }
}

class ExposedStepRepository(
    private val db: DatabaseConnection,
) : StepRepository {
    override fun upsert(row: StepRow) {
        db.query {
            val updated =
                PythiaStepsTable.update({
                    (PythiaStepsTable.investigationId eq row.investigationId.k()) and
                        (PythiaStepsTable.stepId eq row.stepId)
                }) {
                    it[nodeId] = row.nodeId
                    it[body] = row.bodyJson
                    it[status] = row.status
                    it[outputHandle] = row.outputHandleJson
                }
            if (updated == 0) {
                PythiaStepsTable.insert {
                    it[investigationId] = row.investigationId.k()
                    it[stepId] = row.stepId
                    it[nodeId] = row.nodeId
                    it[body] = row.bodyJson
                    it[status] = row.status
                    it[outputHandle] = row.outputHandleJson
                }
            }
        }
    }

    override fun findByInvestigation(investigationId: UUID): List<StepRow> =
        db.query {
            PythiaStepsTable
                .selectAll()
                .where { PythiaStepsTable.investigationId eq investigationId.k() }
                .orderBy(PythiaStepsTable.stepId to SortOrder.ASC)
                .map {
                    StepRow(
                        investigationId = it[PythiaStepsTable.investigationId].j(),
                        stepId = it[PythiaStepsTable.stepId],
                        nodeId = it[PythiaStepsTable.nodeId],
                        bodyJson = it[PythiaStepsTable.body],
                        status = it[PythiaStepsTable.status],
                        outputHandleJson = it[PythiaStepsTable.outputHandle],
                    )
                }
        }
}

class ExposedHandleRepository(
    private val db: DatabaseConnection,
) : HandleRepository {
    override fun insert(row: HandleRow) {
        db.query {
            PythiaHandlesTable.insert {
                it[investigationId] = row.investigationId.k()
                it[handleId] = row.handleId
                it[kind] = row.kind
                it[body] = row.bodyJson
                it[inlineData] = row.inlineData
            }
        }
    }

    override fun findById(
        investigationId: UUID,
        handleId: String,
    ): HandleRow? =
        db.query {
            PythiaHandlesTable
                .selectAll()
                .where {
                    (PythiaHandlesTable.investigationId eq investigationId.k()) and
                        (PythiaHandlesTable.handleId eq handleId)
                }.singleOrNull()
                ?.toHandleRow()
        }

    override fun findByInvestigation(investigationId: UUID): List<HandleRow> =
        db.query {
            PythiaHandlesTable
                .selectAll()
                .where { PythiaHandlesTable.investigationId eq investigationId.k() }
                .orderBy(PythiaHandlesTable.handleId to SortOrder.ASC)
                .map { it.toHandleRow() }
        }

    private fun ResultRow.toHandleRow(): HandleRow =
        HandleRow(
            investigationId = this[PythiaHandlesTable.investigationId].j(),
            handleId = this[PythiaHandlesTable.handleId],
            kind = this[PythiaHandlesTable.kind],
            bodyJson = this[PythiaHandlesTable.body],
            inlineData = this[PythiaHandlesTable.inlineData],
        )
}

class ExposedCheckpointRepository(
    private val db: DatabaseConnection,
) : CheckpointRepository {
    override fun append(row: CheckpointRow) {
        db.query {
            PythiaCheckpointsTable.insert {
                it[investigationId] = row.investigationId.k()
                it[seq] = row.seq
                it[reason] = row.reason
                it[schedulerState] = row.schedulerStateJson
                it[diff] = row.diffJson
                it[takenAt] = row.takenAt.odt()
            }
        }
    }

    override fun latestSeq(investigationId: UUID): Int? =
        db.query {
            val maxExpr = PythiaCheckpointsTable.seq.max()
            PythiaCheckpointsTable
                .select(maxExpr)
                .where { PythiaCheckpointsTable.investigationId eq investigationId.k() }
                .firstOrNull()
                ?.get(maxExpr)
        }

    override fun loadAll(investigationId: UUID): List<CheckpointRow> =
        db.query {
            PythiaCheckpointsTable
                .selectAll()
                .where { PythiaCheckpointsTable.investigationId eq investigationId.k() }
                .orderBy(PythiaCheckpointsTable.seq to SortOrder.ASC)
                .map {
                    CheckpointRow(
                        investigationId = it[PythiaCheckpointsTable.investigationId].j(),
                        seq = it[PythiaCheckpointsTable.seq],
                        reason = it[PythiaCheckpointsTable.reason],
                        schedulerStateJson = it[PythiaCheckpointsTable.schedulerState],
                        diffJson = it[PythiaCheckpointsTable.diff],
                        takenAt = it[PythiaCheckpointsTable.takenAt].toInstant(),
                    )
                }
        }
}

class ExposedEventRepository(
    private val db: DatabaseConnection,
) : EventRepository {
    override fun append(
        investigationId: UUID,
        kind: String,
        payloadJson: String,
        emittedAtMillis: Long,
    ): Long {
        // `max(sequence)+1` is computed inside the transaction; the (investigation_id,
        // sequence) PK rejects a racing duplicate with a 23505, on which we recompute the
        // next sequence and retry. Concurrent emitters therefore serialise gap-free rather
        // than failing the investigation (H4). Bounded so a genuine fault still surfaces.
        var attempt = 0
        while (true) {
            try {
                return db.query {
                    val maxExpr = PythiaEventsTable.sequence.max()
                    val current =
                        PythiaEventsTable
                            .select(maxExpr)
                            .where { PythiaEventsTable.investigationId eq investigationId.k() }
                            .firstOrNull()
                            ?.get(maxExpr)
                    val seq = (current ?: -1L) + 1L
                    PythiaEventsTable.insert {
                        it[PythiaEventsTable.investigationId] = investigationId.k()
                        it[sequence] = seq
                        it[emittedAt] = Instant.ofEpochMilli(emittedAtMillis).odt()
                        it[PythiaEventsTable.kind] = kind
                        it[payload] = payloadJson
                    }
                    seq
                }
            } catch (e: Exception) {
                if (isUniqueViolation(e) && attempt++ < MAX_SEQ_RETRIES) continue
                throw e
            }
        }
    }

    private companion object {
        const val MAX_SEQ_RETRIES = 16
    }

    override fun replay(
        investigationId: UUID,
        fromSeq: Long,
    ): List<EventRow> =
        db.query {
            PythiaEventsTable
                .selectAll()
                .where {
                    (PythiaEventsTable.investigationId eq investigationId.k()) and
                        (PythiaEventsTable.sequence greaterEq fromSeq)
                }.orderBy(PythiaEventsTable.sequence to SortOrder.ASC)
                .map {
                    EventRow(
                        investigationId = it[PythiaEventsTable.investigationId].j(),
                        sequence = it[PythiaEventsTable.sequence],
                        emittedAt = it[PythiaEventsTable.emittedAt].toInstant(),
                        kind = it[PythiaEventsTable.kind],
                        payloadJson = it[PythiaEventsTable.payload],
                    )
                }
        }
}

private fun isUniqueViolation(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.any { (it as? java.sql.SQLException)?.sqlState == "23505" }
