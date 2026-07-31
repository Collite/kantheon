package org.tatrman.kantheon.iris.protocol.record

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.protocol.v1.RecordCaptures
import org.tatrman.kantheon.protocol.v1.RecordPointers
import java.util.UUID

/**
 * [ProtocolRecordStore] behavioural contract, run against the in-memory fake
 * (mocked-unit policy, planning-conventions §4 — no live PG in CI).
 * `ExposedProtocolRecordStore` must satisfy the same cases; real-PG fidelity is
 * the integration tier's job.
 */
class ProtocolRecordStoreSpec :
    StringSpec({

        fun record(
            turnId: UUID,
            trace: String = "trace-1",
            schemaVersion: String = SchemaVersion.CURRENT,
        ): ProtocolRecord =
            ProtocolRecord
                .newBuilder()
                .setTurnId(turnId.toString())
                .setPointers(RecordPointers.newBuilder().setTraceId(trace).setGatewayTurnRef("gw-$trace"))
                .setCaptures(
                    RecordCaptures.newBuilder().setResolveResponse(
                        com.google.protobuf.ByteString
                            .copyFromUtf8("f2"),
                    ),
                ).setSchemaVersion(schemaVersion)
                .build()

        "write then readByTurnId returns identical record" {
            val store = InMemoryProtocolRecordStore()
            val turnId = UUID.randomUUID()
            val written = record(turnId)

            store.write(written)

            store.readByTurnId(turnId) shouldBe written
        }

        "readForSession returns records ordered by turn seq" {
            val store = InMemoryProtocolRecordStore()
            val sessionId = UUID.randomUUID()
            val turns = List(4) { UUID.randomUUID() }

            // Link and write out of order — ordering must come from seq, not from
            // insertion order or map iteration order.
            listOf(2, 0, 3, 1).forEach { i ->
                store.linkTurn(turns[i], sessionId, seq = i + 1)
                store.write(record(turns[i], trace = "t${i + 1}"))
            }

            store.readForSession(sessionId).map { it.pointers.traceId } shouldContainExactly
                listOf("t1", "t2", "t3", "t4")

            // lastN takes the most recent N, still oldest -> newest.
            store.readForSession(sessionId, lastN = 2).map { it.pointers.traceId } shouldContainExactly
                listOf("t3", "t4")

            // A turn from another session, and a discarded one, are not in scope.
            val other = UUID.randomUUID()
            val otherTurn = UUID.randomUUID()
            store.linkTurn(otherTurn, other, seq = 1)
            store.write(record(otherTurn, trace = "other"))

            val discarded = UUID.randomUUID()
            store.linkTurn(discarded, sessionId, seq = 5, discarded = true)
            store.write(record(discarded, trace = "gone"))

            store.readForSession(sessionId).map { it.pointers.traceId } shouldContainExactly
                listOf("t1", "t2", "t3", "t4")
            store.readForSession(other).map { it.pointers.traceId } shouldContainExactly listOf("other")

            // An unlinked turn has a record but belongs to no session — the SQL
            // inner join drops it too.
            store.write(record(UUID.randomUUID(), trace = "orphan"))
            store.readForSession(sessionId).map { it.pointers.traceId } shouldContainExactly
                listOf("t1", "t2", "t3", "t4")
        }

        "write is idempotent per turn_id (upsert, last write wins)" {
            val store = InMemoryProtocolRecordStore()
            val sessionId = UUID.randomUUID()
            val turnId = UUID.randomUUID()
            store.linkTurn(turnId, sessionId, seq = 1)

            store.write(record(turnId, trace = "first"))
            store.write(record(turnId, trace = "second"))

            store.readForSession(sessionId).size shouldBe 1
            store.readByTurnId(turnId)!!.pointers.traceId shouldBe "second"
        }

        "readByTurnId for unknown turn returns null" {
            val store = InMemoryProtocolRecordStore()
            store.readByTurnId(UUID.randomUUID()).shouldBeNull()
            store.readForSession(UUID.randomUUID()).shouldBeEmpty()
        }

        "migration table shape: insert row with pointers/captures/schema_version succeeds" {
            // The three NOT NULL columns of contracts §6.1 must all survive a
            // round trip through the store, including the empty-captures case the
            // DDL defaults to '{}'::jsonb.
            val store = InMemoryProtocolRecordStore()
            val turnId = UUID.randomUUID()
            val bare =
                ProtocolRecord
                    .newBuilder()
                    .setTurnId(turnId.toString())
                    .setPointers(RecordPointers.newBuilder().setTraceId("trace-only"))
                    .build()

            store.write(bare)

            val read = store.readByTurnId(turnId)!!
            read.pointers.traceId shouldBe "trace-only"
            read.captures shouldBe RecordCaptures.getDefaultInstance()
            read.schemaVersion shouldBe SchemaVersion.CURRENT
        }
    })
