package org.tatrman.kantheon.iris.infra

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.tatrman.kantheon.iris.audit.AuditRecord
import org.tatrman.kantheon.iris.audit.AuditStore
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.GENESIS
import org.tatrman.kantheon.iris.audit.signedRecord
import shared.libs.db.common.DatabaseConnection
import java.time.Instant
import java.time.ZoneOffset

/**
 * Postgres-backed [AuditStore] — the live `iris_audit` writer (the in-memory
 * store backs the unit/component gate). Append takes a transaction-scoped
 * Postgres **advisory lock** so the `prev_hash` read + insert is atomic against
 * the chain tail under REPEATABLE READ; without it two concurrent appends could
 * both read the same tail and fork the chain. `seq` is a DB IDENTITY, read back
 * after insert (and not part of the hash — see [signedRecord]).
 *
 * Real-PG fidelity is exercised in the integration-test suite
 * (planning-conventions §4); the unit/component gate runs against
 * [org.tatrman.kantheon.iris.audit.InMemoryAuditStore], which shares the same
 * [signedRecord] chaining logic.
 */
class ExposedAuditStore(
    private val db: DatabaseConnection,
    private val signer: Ed25519Signer,
) : AuditStore {
    override fun append(
        userId: String,
        eventKind: String,
        payloadJson: String,
        ts: Instant,
    ): AuditRecord =
        db.query {
            // Serialise chain appends within this transaction (key is a constant
            // namespaced to the iris-audit chain). Released at transaction end.
            TransactionManager.current().exec("SELECT pg_advisory_xact_lock($CHAIN_LOCK_KEY)")
            val prevHash =
                IrisAudit
                    .selectAll()
                    .orderBy(IrisAudit.seq to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(IrisAudit.selfHash) ?: GENESIS
            val draft = signedRecord(0L, ts, userId, eventKind, payloadJson, prevHash, signer)
            val inserted =
                IrisAudit.insert {
                    it[IrisAudit.ts] = ts.atOffset(ZoneOffset.UTC)
                    it[IrisAudit.userId] = userId
                    it[IrisAudit.eventKind] = eventKind
                    it[IrisAudit.payload] = draft.payloadJson
                    it[IrisAudit.segment] = draft.segment
                    it[IrisAudit.prevHash] = draft.prevHash
                    it[IrisAudit.selfHash] = draft.selfHash
                    it[IrisAudit.sig] = draft.sig
                }
            draft.copy(seq = inserted[IrisAudit.seq])
        }

    override fun all(): List<AuditRecord> =
        db.query {
            IrisAudit
                .selectAll()
                .orderBy(IrisAudit.seq to SortOrder.ASC)
                .map { it.toRecord() }
        }

    private fun ResultRow.toRecord() =
        AuditRecord(
            seq = this[IrisAudit.seq],
            ts = this[IrisAudit.ts].toInstant(),
            userId = this[IrisAudit.userId],
            eventKind = this[IrisAudit.eventKind],
            payloadJson = this[IrisAudit.payload],
            segment = this[IrisAudit.segment],
            prevHash = this[IrisAudit.prevHash],
            selfHash = this[IrisAudit.selfHash],
            sig = this[IrisAudit.sig],
        )

    private companion object {
        // Arbitrary constant identifying the iris-audit chain to pg_advisory_xact_lock.
        const val CHAIN_LOCK_KEY = 7410_0001L
    }
}
