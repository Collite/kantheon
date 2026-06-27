package org.tatrman.kantheon.pythia.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a status string is an AWAITING_* pause (its `awaiting*` columns are
 * cleared on transition out). Matches both the canonical proto names
 * (`STATUS_AWAITING_*`) and bare `AWAITING_*` forms by the `AWAITING_` token — no
 * non-pause status carries it, so this stays precise without enumerating both forms.
 */
internal fun String.isAwaiting(): Boolean = contains("AWAITING_")

/**
 * In-memory [InvestigationRepository] — the unit/component fake and the
 * local-boot store when `pythia.db.enabled = false`. Mirrors the Exposed
 * binding's behaviour (planning-conventions §4).
 */
class InMemoryInvestigationRepository : InvestigationRepository {
    private val byId = ConcurrentHashMap<UUID, InvestigationRecord>()
    private val locks = ConcurrentHashMap<UUID, Any>()

    private fun lockFor(id: UUID): Any = locks.computeIfAbsent(id) { Any() }

    override fun insert(record: InvestigationRecord) {
        if (byId.putIfAbsent(record.id, record) != null) throw DuplicateInvestigationException(record.id)
    }

    override fun findById(id: UUID): InvestigationRecord? = byId[id]

    override fun save(record: InvestigationRecord) {
        synchronized(lockFor(record.id)) {
            // Parity with the Exposed UPDATE: no-op on a missing row (never an upsert).
            // `status` is owned exclusively by compareAndSetStatus — save preserves the
            // stored status so a stale in-memory snapshot can never roll a transition back.
            val existing = byId[record.id] ?: return
            byId[record.id] = record.copy(status = existing.status, updatedAt = Instant.now())
        }
    }

    override fun compareAndSetStatus(
        id: UUID,
        from: String,
        to: String,
    ): Boolean {
        synchronized(lockFor(id)) {
            val current = byId[id] ?: return false
            if (current.status != from) return false
            val leaving = from.isAwaiting()
            byId[id] =
                current.copy(
                    status = to,
                    awaitingSince = if (leaving) null else current.awaitingSince,
                    awaitingTtlUntil = if (leaving) null else current.awaitingTtlUntil,
                    updatedAt = Instant.now(),
                )
            return true
        }
    }

    override fun list(
        userId: String,
        statuses: Set<String>,
        page: Int,
        pageSize: Int,
    ): InvestigationPage {
        val matching =
            byId.values
                .filter { userIdOf(it) == userId && (statuses.isEmpty() || it.status in statuses) }
                .sortedWith(compareByDescending<InvestigationRecord> { it.createdAt }.thenByDescending { it.id })
        val from = page * pageSize
        val slice = matching.drop(from).take(pageSize)
        val nextPage = if (from + pageSize < matching.size) page + 1 else null
        return InvestigationPage(slice, nextPage)
    }

    override fun findExpiredAwaiting(nowEpochMillis: Long): List<InvestigationRecord> =
        byId.values.filter {
            it.status.isAwaiting() &&
                it.awaitingTtlUntil != null &&
                it.awaitingTtlUntil.toEpochMilli() < nowEpochMillis
        }

    private fun userIdOf(r: InvestigationRecord): String? =
        runCatching {
            JSON
                .parseToJsonElement(r.callerJson)
                .jsonObject["userId"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

class InMemoryHypothesisRepository : HypothesisRepository {
    private val byKey = ConcurrentHashMap<Pair<UUID, String>, HypothesisRecord>()

    override fun upsert(record: HypothesisRecord) {
        byKey[record.investigationId to record.hypId] = record
    }

    override fun findByInvestigation(investigationId: UUID): List<HypothesisRecord> =
        byKey.values.filter { it.investigationId == investigationId }.sortedBy { it.hypId }
}

class InMemoryStepRepository : StepRepository {
    private val byKey = ConcurrentHashMap<Pair<UUID, String>, StepRow>()

    override fun upsert(row: StepRow) {
        byKey[row.investigationId to row.stepId] = row
    }

    override fun findByInvestigation(investigationId: UUID): List<StepRow> =
        byKey.values.filter { it.investigationId == investigationId }.sortedBy { it.stepId }
}

class InMemoryHandleRepository : HandleRepository {
    private val byKey = ConcurrentHashMap<Pair<UUID, String>, HandleRow>()

    override fun insert(row: HandleRow) {
        byKey[row.investigationId to row.handleId] = row
    }

    override fun findById(
        investigationId: UUID,
        handleId: String,
    ): HandleRow? = byKey[investigationId to handleId]

    override fun findByInvestigation(investigationId: UUID): List<HandleRow> =
        byKey.values.filter { it.investigationId == investigationId }.sortedBy { it.handleId }
}

class InMemoryCheckpointRepository : CheckpointRepository {
    private val byInv = ConcurrentHashMap<UUID, MutableList<CheckpointRow>>()

    @Synchronized
    override fun append(row: CheckpointRow) {
        byInv.computeIfAbsent(row.investigationId) { mutableListOf() }.add(row)
    }

    override fun latestSeq(investigationId: UUID): Int? = byInv[investigationId]?.maxOfOrNull { it.seq }

    override fun loadAll(investigationId: UUID): List<CheckpointRow> =
        byInv[investigationId]?.sortedBy { it.seq } ?: emptyList()
}

class InMemoryEventRepository : EventRepository {
    private val byInv = ConcurrentHashMap<UUID, MutableList<EventRow>>()

    override fun append(
        investigationId: UUID,
        kind: String,
        payloadJson: String,
        emittedAtMillis: Long,
    ): Long {
        val list = byInv.computeIfAbsent(investigationId) { mutableListOf() }
        synchronized(list) {
            val seq = (list.maxOfOrNull { it.sequence } ?: -1L) + 1L
            list.add(EventRow(investigationId, seq, Instant.ofEpochMilli(emittedAtMillis), kind, payloadJson))
            return seq
        }
    }

    override fun replay(
        investigationId: UUID,
        fromSeq: Long,
    ): List<EventRow> =
        byInv[investigationId]
            ?.filter { it.sequence >= fromSeq }
            ?.sortedBy { it.sequence }
            ?: emptyList()
}
