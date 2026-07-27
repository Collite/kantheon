package org.tatrman.kantheon.iris.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.PendingClarification
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentClient
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification
import org.tatrman.kantheon.themis.v1.Themis.ClarificationOption
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
import java.io.IOException

/**
 * A client that goes away mid-turn (closed tab, reaped proxy connection, a browser that
 * gave up during a slow resolve) must not cost the turn.
 *
 * Every branch of [ChatDispatcher] emits before it persists, so an exception from the
 * emit sink used to propagate out of `runTurn` and skip `persist` altogether — the turn
 * left no `iris_turns` row, no audit row, and no `pending_resume_token`, which is what
 * makes an open clarification unresumable even after the user reconnects. Observed live
 * as `ChannelWriteException: Cannot write to channel` surfacing as
 * "Unhandled error on /v1/chat/stream".
 */
class ChatDispatcherClientLossSpec :
    StringSpec({

        /** An emit sink that accepts [acceptFirst] events, then behaves like a dead socket. */
        class DeadClient(
            private val acceptFirst: Int = 0,
        ) {
            var accepted = 0

            suspend fun emit(
                @Suppress("UNUSED_PARAMETER") ev: IrisStreamEvent,
            ) {
                if (accepted >= acceptFirst) throw IOException("Cannot write to channel")
                accepted++
            }
        }

        fun clarificationResponse(): ResolveResponse =
            ResolveResponse
                .newBuilder()
                .setAwaiting(
                    AwaitingClarification
                        .newBuilder()
                        .setQuestion("Kterého zákazníka myslíte?")
                        .addOptions(ClarificationOption.newBuilder().setLabel("Kaufland ČR v.o.s.")),
                ).build()

        /** An agent that answers with a clarification carrying a resume token. */
        class ClarifyingAgent : AgentClient {
            override suspend fun runTurn(
                turn: AgentTurn,
                emit: suspend (IrisStreamEvent) -> Unit,
            ): TurnOutcome {
                val env =
                    FormatEnvelope
                        .newBuilder()
                        .setBubbleId("b-1")
                        .setTurnId(turn.turnId)
                        .setText("Za jaké období?")
                        .setPendingClarification(
                            PendingClarification.newBuilder().setKind("missing_arg").setResumeToken("rt-1"),
                        ).build()
                emit(IrisStreamEvent.newBuilder().setEnvelope(env).build())
                return TurnOutcome(env, TurnStatus.CLARIFICATION, "rt-1", null, "clarification")
            }

            override suspend fun runResume(
                resume: AgentResume,
                emit: suspend (IrisStreamEvent) -> Unit,
            ): TurnOutcome = error("not used")
        }

        fun dispatcher(
            store: InMemorySessionStore,
            audit: InMemoryAuditStore,
            themis: FakeThemisClient,
            agents: Map<String, AgentClient> = emptyMap(),
        ) = ChatDispatcher(
            store,
            themis,
            AgentDispatcher(agents),
            audit,
            RoutingEnvelopes(AgentLabels.IDENTITY),
        )

        "a Themis clarification is still persisted when the client is already gone" {
            runTest {
                val store = InMemorySessionStore()
                val audit = InMemoryAuditStore(Ed25519Signer())
                val themis = FakeThemisClient(responder = { clarificationResponse() })
                val session = store.createSession("maya", "hartland")
                val dead = DeadClient()

                val outcome =
                    dispatcher(store, audit, themis).runTurn(
                        caller = CallerIdentity("maya", "hartland", "jwt-1"),
                        sessionId = session.sessionId,
                        question = "kolik prodali?",
                        desiredFormat = null,
                        correlationId = "corr-1",
                        emit = dead::emit,
                    )

                // The turn completed server-side despite the very first write failing.
                dead.accepted shouldBe 0
                outcome.status shouldBe TurnStatus.DONE
                val turns = store.getTurns(session.sessionId)
                turns.size shouldBe 1
                turns.single().envelopeJson shouldNotBe null
                audit.all().size shouldBe 1
            }
        }

        "an open clarification keeps its resume token, so it survives a reconnect" {
            runTest {
                val store = InMemorySessionStore()
                val audit = InMemoryAuditStore(Ed25519Signer())
                val themis = FakeThemisClient(defaultAgent = "golem-hartland")
                val session = store.createSession("maya", "hartland")
                // Dead on the agent's own envelope write — i.e. inside the dispatch path,
                // not just on the BFF's pre-dispatch frames.
                val dead = DeadClient()

                val outcome =
                    dispatcher(store, audit, themis, mapOf("golem-hartland" to ClarifyingAgent())).runTurn(
                        caller = CallerIdentity("maya", "hartland", "jwt-1"),
                        sessionId = session.sessionId,
                        question = "tržby?",
                        desiredFormat = null,
                        correlationId = "corr-1",
                        emit = dead::emit,
                    )

                outcome.status shouldBe TurnStatus.CLARIFICATION
                // This is the part that was being lost: without it the user reconnects to a
                // clarification the BFF no longer knows is open.
                store.getTurns(session.sessionId).single().pendingResumeToken shouldBe "rt-1"
                dispatcher(store, audit, themis).resumeIssuer(session.sessionId, "rt-1") shouldBe "golem-hartland"
            }
        }

        "a Themis refusal is still persisted when the client is gone" {
            runTest {
                val store = InMemorySessionStore()
                val audit = InMemoryAuditStore(Ed25519Signer())
                val themis =
                    FakeThemisClient(
                        responder = {
                            FakeThemisClient.refusal(
                                listOf(org.tatrman.kantheon.themis.v1.Themis.GapKind.NO_ENTITLED_AGENT to "none"),
                            )
                        },
                    )
                val session = store.createSession("maya", "hartland")

                val outcome =
                    dispatcher(store, audit, themis).runTurn(
                        caller = CallerIdentity("maya", "hartland", "jwt-1"),
                        sessionId = session.sessionId,
                        question = "cokoliv",
                        desiredFormat = null,
                        correlationId = "corr-1",
                        emit = DeadClient()::emit,
                    )

                outcome.status shouldBe TurnStatus.FAILED
                store.getTurns(session.sessionId).single().status shouldBe TurnStatus.FAILED
                audit.all().size shouldBe 1
            }
        }

        "cancellation is NOT swallowed — the call must still unwind" {
            runTest {
                val store = InMemorySessionStore()
                val audit = InMemoryAuditStore(Ed25519Signer())
                val themis = FakeThemisClient(responder = { clarificationResponse() })
                val session = store.createSession("maya", "hartland")

                shouldThrow<CancellationException> {
                    dispatcher(store, audit, themis).runTurn(
                        caller = CallerIdentity("maya", "hartland", "jwt-1"),
                        sessionId = session.sessionId,
                        question = "kolik prodali?",
                        desiredFormat = null,
                        correlationId = "corr-1",
                        emit = { throw CancellationException("call cancelled") },
                    )
                }
            }
        }
    })
