package org.tatrman.kantheon.iris.protocol.record

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import java.util.UUID

/**
 * [ProtocolRecorder] — the write path (architecture §1/§7). The two invariants
 * under test are the ones that would hurt in production if they regressed:
 * F2 is captured whole on every Themis outcome, and a store failure is
 * absorbed rather than propagated into the user's turn.
 */
class ProtocolRecorderSpec :
    StringSpec({

        "RESOLUTION outcome: record row written with serialized ResolveResponse in captures.resolve_response" {
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext(resolveResponse = RecorderFixtures.resolution())

            ProtocolRecorder(store).record(ctx)

            val row = store.readByTurnId(ctx.turnId)!!
            row.turnId shouldBe ctx.turnId.toString()
            row.schemaVersion shouldBe SchemaVersion.CURRENT

            // Captured whole, not projected: parsing the bytes back must yield the
            // exact ResolveResponse Themis returned, routing decision included.
            val restored = ResolveResponse.parseFrom(row.captures.resolveResponse)
            restored shouldBe RecorderFixtures.resolution()
            restored.resolution.routing.chosenAgentId.value shouldBe "golem-finance"
        }

        "AWAITING outcome: record still written (F2 capture = the awaiting ResolveResponse)" {
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext(resolveResponse = RecorderFixtures.awaiting(), hints = null)

            ProtocolRecorder(store).record(ctx)

            val row = store.readByTurnId(ctx.turnId)!!
            ResolveResponse.parseFrom(row.captures.resolveResponse).outcomeCase shouldBe
                ResolveResponse.OutcomeCase.AWAITING
        }

        "REFUSAL outcome: record written; pointers present, hints empty" {
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext(resolveResponse = RecorderFixtures.refusal(), hints = null)

            ProtocolRecorder(store).record(ctx)

            val row = store.readByTurnId(ctx.turnId)!!
            ResolveResponse.parseFrom(row.captures.resolveResponse).outcomeCase shouldBe
                ResolveResponse.OutcomeCase.REFUSAL

            // Pointers still populated — a refused turn has an execution path worth
            // narrating even though no agent ran.
            row.pointers.gatewayTurnRef shouldBe ctx.turnId.toString()
            row.pointers.logWindowFrom shouldBe "2026-07-30T09:00:03Z"
            row.pointers.logWindowTo shouldBe "2026-07-30T09:00:13Z"

            row.pointers.hasHints() shouldBe true
            row.pointers.hints.planIdsList
                .shouldBeEmpty()
            row.pointers.hints.llmCallRefsList
                .shouldBeEmpty()
            row.pointers.planIdsList.shouldBeEmpty()
        }

        "store throws -> turn result unaffected, iris_protocol_record_write_failures_total incremented, error logged" {
            val exploding =
                object : ProtocolRecordStore {
                    override fun write(record: ProtocolRecord) = error("PG is down")

                    override fun readByTurnId(turnId: UUID): ProtocolRecord? = null

                    override fun readForSession(
                        sessionId: UUID,
                        lastN: Int?,
                    ): List<ProtocolRecord> = emptyList()
                }
            val registry = SimpleMeterRegistry()

            // The assertion IS that this does not throw — the recorder is called
            // inline on the turn path, so a rethrow would fail a live answer.
            ProtocolRecorder(exploding, registry).record(turnContext())

            registry.counter("iris_protocol_record_write_failures_total").count() shouldBe 1.0
        }

        "hints from ConversationalResponse.protocol_hints copied verbatim into pointers.hints (PT-25)" {
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext(hints = RecorderFixtures.hints())

            ProtocolRecorder(store).record(ctx)

            val pointers = store.readByTurnId(ctx.turnId)!!.pointers

            // Verbatim: byte-identical to what the agent sent, nothing dropped or reordered.
            pointers.hints shouldBe RecorderFixtures.hints()

            // ...and hoisted to the top level so the assembler reads one place.
            pointers.planIdsList shouldContainExactly listOf("plan-7")
            pointers.llmCallRefsList shouldContainExactly listOf("gw-771", "gw-772")
            pointers.sqlInline shouldBe "SELECT 1"
            pointers.sqlRef shouldBe ""
        }

        // ---- Amendment A-1 (T4): F7 is structurally unreachable; record the gap ----

        "captures.security_applied empty + capture-absent marker recorded exactly as Amendment A-1 specifies" {
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext()

            ProtocolRecorder(store).record(ctx)

            val row = store.readByTurnId(ctx.turnId)!!

            // Empty, because nothing upstream carries it.
            row.captures.securityApplied.isEmpty shouldBe true

            // But NOT silently empty — "no rules applied" and "we could not look"
            // are different facts, and only the marker distinguishes them.
            row.pointers.captureGapsList.map { it.capture } shouldContainExactly listOf("security_applied")
            row.pointers.captureGapsList
                .single()
                .reason shouldBe
                "the query service does not propagate validate.v1 security_applied to callers (A-1)"
            row.pointers.captureGapsList.single() shouldBe ProtocolRecorder.SECURITY_APPLIED_GAP
        }

        "a turn with no Themis response at all still records pointers, with no F2 capture" {
            // Defensive: TurnRecordContext.resolveResponse is nullable, and a null
            // must produce an empty capture rather than an exception or a fake.
            val store = InMemoryProtocolRecordStore()
            val ctx = turnContext(resolveResponse = null, hints = null)

            ProtocolRecorder(store).record(ctx)

            val row = store.readByTurnId(ctx.turnId)!!
            row.captures.resolveResponse.isEmpty shouldBe true
            row.pointers.gatewayTurnRef shouldBe ctx.turnId.toString()
            store.readByTurnId(UUID.randomUUID()).shouldBeNull()
        }
    })
