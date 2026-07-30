package org.tatrman.kantheon.iris.protocol.record

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.api.ChatDispatcher
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golem.GolemFixtures
import org.tatrman.kantheon.iris.dispatch.golem.GolemResume
import org.tatrman.kantheon.iris.dispatch.golem.GolemSseParser
import org.tatrman.kantheon.iris.dispatch.golem.GolemV1AgentClient
import org.tatrman.kantheon.iris.dispatch.golem.GolemV1Client
import org.tatrman.kantheon.iris.dispatch.golem.GolemV1Event
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisClient
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.protocol.v1.ProtocolHints
import org.tatrman.kantheon.protocol.v1.ProtocolRecord
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import java.util.UUID

/**
 * The Phase-1 **code** gate (plan §2): a dispatched turn writes exactly one
 * `iris_protocol_records` row, asserted field by field against the canned
 * inputs. Wired through the real [ChatDispatcher] with a scripted Themis and a
 * scripted golem — no live Themis/golem/PG (mocked-unit policy,
 * planning-conventions §4).
 *
 * Deliberately over the **golem v1** path rather than the transitional /v2 one:
 * v1's terminal frame is a `ConversationalResponse`, which is where
 * `protocol_hints` lives (contracts §4). The /v2 JSON wire has no hints field at
 * all, so a v2-based test could not prove the PT-25 verbatim-carry at all.
 *
 * *(The same observed on a live cluster is the P1 live gate — plan §5.2.)*
 */
class ProtocolCaptureComponentSpec :
    StringSpec({

        val agentId = "golem-hartland"

        /** Scripted golem: replays a canned ConversationalResponse as an SSE flow. */
        class ScriptedGolem(
            private val response: ConversationalResponse,
        ) : GolemV1Client {
            override fun answer(
                request: GolemRequest,
                correlationId: String,
                bearer: String,
            ): Flow<GolemV1Event> = GolemSseParser.parse(GolemFixtures.sseBody(response)).asFlow()

            override fun resume(
                request: GolemResume,
                correlationId: String,
                bearer: String,
            ): Flow<GolemV1Event> = emptyList<GolemV1Event>().asFlow()
        }

        class Harness(
            themis: ThemisClient = FakeThemisClient(defaultAgent = agentId),
            response: ConversationalResponse = withHints(GolemFixtures.response()),
            val records: ProtocolRecordStore = InMemoryProtocolRecordStore(),
        ) {
            val store = InMemorySessionStore()
            private val agents =
                AgentDispatcher(mapOf(agentId to GolemV1AgentClient(agentId, store, ScriptedGolem(response))))
            val dispatcher =
                ChatDispatcher(
                    store,
                    themis,
                    agents,
                    InMemoryAuditStore(Ed25519Signer()),
                    RoutingEnvelopes(AgentLabels.IDENTITY),
                    protocolRecorder = ProtocolRecorder(records),
                )
            val session = store.createSession("u1", "t1")
            val caller = CallerIdentity("u1", "t1", "jwt")

            suspend fun turn(question: String = "how did margin move?"): List<IrisStreamEvent> {
                val out = mutableListOf<IrisStreamEvent>()
                dispatcher.runTurn(caller, session.sessionId, question, null, "corr-9", null, null, out::add)
                return out
            }
        }

        "H1-style turn: dispatch -> exactly one iris_protocol_records row for the turn" {
            runTest {
                val h = Harness()
                h.turn()

                val turn = h.store.getTurns(h.session.sessionId).single()
                val row = h.records.readByTurnId(turn.turnId)

                // Exactly one, and keyed to the turn that was actually persisted —
                // which is also what proves write-after-commit: the FK target exists.
                row!!.turnId shouldBe turn.turnId.toString()
            }
        }

        "row field-by-field: turn_id, pointers, hints verbatim, captures per A-1, schema_version" {
            runTest {
                val h = Harness()
                h.turn()

                val turn = h.store.getTurns(h.session.sessionId).single()
                val row = h.records.readByTurnId(turn.turnId)!!
                val p = row.pointers

                row.schemaVersion shouldBe "protocol/v1.0"
                p.gatewayTurnRef shouldBe turn.turnId.toString()
                p.correlationId shouldBe "corr-9"

                // No span is current in this test, so trace_id degrades to "" rather
                // than fabricating one — the documented fallback (PointerSourcing).
                p.traceId shouldBe ""

                // Window is real ISO-8601 and brackets the turn.
                p.logWindowFrom.isNotEmpty() shouldBe true
                p.logWindowTo.isNotEmpty() shouldBe true
                java.time.Instant
                    .parse(p.logWindowFrom)
                    .isBefore(java.time.Instant.parse(p.logWindowTo)) shouldBe true

                // PT-25: the agent's block, byte-identical.
                p.hints shouldBe CANNED_HINTS
                p.planIdsList shouldContainExactly listOf("plan-hartland-1")
                p.llmCallRefsList shouldContainExactly listOf("gw-100", "gw-101")
                p.sqlInline shouldBe "SELECT margin FROM p_and_l"
                p.sqlRef shouldBe ""

                // F2 captured whole and parseable back.
                ResolveResponse.parseFrom(row.captures.resolveResponse).outcomeCase shouldBe
                    ResolveResponse.OutcomeCase.RESOLUTION

                // F7 per Amendment A-1: absent, and explicitly marked absent.
                row.captures.securityApplied.isEmpty shouldBe true
                p.captureGapsList.map { it.capture } shouldContainExactly listOf("security_applied")
            }
        }

        "AWAITING turn also produces a row" {
            runTest {
                // A plain Themis clarification (entity/intent ambiguity): the BFF
                // renders PromptChips and never dispatches, so no agent hints exist.
                val h = Harness(themis = FakeThemisClient(responder = { RecorderFixtures.awaiting() }))
                h.turn()

                val turn = h.store.getTurns(h.session.sessionId).single()
                val row = h.records.readByTurnId(turn.turnId)!!

                ResolveResponse.parseFrom(row.captures.resolveResponse).outcomeCase shouldBe
                    ResolveResponse.OutcomeCase.AWAITING

                // No agent ran, so there are no hints to carry — empty, not absent-and-faked.
                row.pointers.hints.planIdsList
                    .shouldBeEmpty()
            }
        }

        "record store failure: the turn still completes normally" {
            runTest {
                val exploding =
                    object : ProtocolRecordStore {
                        override fun write(record: ProtocolRecord) = error("PG is down")

                        override fun readByTurnId(turnId: UUID): ProtocolRecord? = null

                        override fun readForSession(
                            sessionId: UUID,
                            lastN: Int?,
                        ): List<ProtocolRecord> = emptyList()
                    }
                val h = Harness(records = exploding)

                val emitted = h.turn()

                // The user's turn is untouched by the observability write failing:
                // envelope delivered, done emitted, turn persisted DONE.
                emitted.count { it.hasEnvelope() } shouldBe 1
                emitted.last().hasDone() shouldBe true
                emitted.last().done.outcome shouldBe "done"
                h.store
                    .getTurns(h.session.sessionId)
                    .single()
                    .status shouldBe TurnStatus.DONE
            }
        }
    }) {
    companion object {
        val CANNED_HINTS: ProtocolHints =
            ProtocolHints
                .newBuilder()
                .addPlanIds("plan-hartland-1")
                .addLlmCallRefs("gw-100")
                .addLlmCallRefs("gw-101")
                .setSqlInline("SELECT margin FROM p_and_l")
                .addTimings(
                    org.tatrman.kantheon.protocol.v1.HintTiming
                        .newBuilder()
                        .setStep("execute")
                        .setDurationMs(180),
                ).build()

        /** The canned golem answer, with the PT-25 hints block attached. */
        fun withHints(response: ConversationalResponse): ConversationalResponse =
            response
                .toBuilder()
                .setStatus(Status.STATUS_DONE)
                .setProtocolHints(CANNED_HINTS)
                .build()
    }
}
