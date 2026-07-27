package org.tatrman.kantheon.iris.dispatch.golem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

class GolemV1MuxSpec :
    StringSpec({

        val sessionId = UUID.fromString("11111111-2222-3333-4444-555555555555")

        suspend fun run(events: List<GolemV1Event>): Pair<List<IrisStreamEvent>, TurnOutcome> {
            val out = mutableListOf<IrisStreamEvent>()
            val outcome = GolemV1Mux().run("turn-1", sessionId, "golem-hartland", events.asFlow(), out::add)
            return out to outcome
        }

        suspend fun runTurn(response: ConversationalResponse) =
            run(GolemSseParser.parse(GolemFixtures.sseBody(response)))

        "happy path: steps → envelope → synthesised done, monotone sequence" {
            runTest {
                val (emitted, outcome) = runTurn(GolemFixtures.response())

                emitted.map { it.sequence } shouldContainExactly (1L..emitted.size.toLong()).toList()
                emitted.all { it.turnId == "turn-1" } shouldBe true
                emitted.last().hasDone() shouldBe true
                emitted.last().done.outcome shouldBe "done"
                outcome.status shouldBe TurnStatus.DONE
                outcome.pendingResumeToken shouldBe null
            }
        }

        "a multi-block turn emits one envelope event per block" {
            runTest {
                val response =
                    GolemFixtures.response(
                        envelopes =
                            listOf(
                                GolemFixtures.textEnvelope("b-1", "Tržby za 2026:"),
                                GolemFixtures.tableEnvelope("b-2"),
                            ),
                    )
                val (emitted, outcome) = runTurn(response)

                emitted.filter { it.hasEnvelope() }.map { it.envelope.bubbleId } shouldContainExactly
                    listOf("b-1", "b-2")
                // The outcome's single envelope — what persists as the turn — is the answer block.
                outcome.envelope?.bubbleId shouldBe "b-2"
            }
        }

        "BFF enrichment stamps thread_id, agent_id and the clarification's issuer" {
            runTest {
                val response =
                    GolemFixtures.response(
                        status = Status.STATUS_CLARIFICATION,
                        envelopes = listOf(GolemFixtures.clarificationEnvelope("rt-1")),
                    )
                val (emitted, _) = runTurn(response)
                val env = emitted.first { it.hasEnvelope() }.envelope

                env.threadId shouldBe sessionId.toString()
                env.agentId shouldBe "golem-hartland"
                env.pendingClarification.issuedByAgentId shouldBe "golem-hartland"
            }
        }

        "a clarification is a clarification even though its envelope carries an error_code" {
            runTest {
                // Golem's param-fill envelope sets BOTH error_code and pending_clarification.
                // Inferring status from the envelope (as the /v2 mux must) reads that as a
                // failed turn and strands the resume token; `status` says otherwise.
                val response =
                    GolemFixtures.response(
                        status = Status.STATUS_CLARIFICATION,
                        envelopes = listOf(GolemFixtures.clarificationEnvelope("rt-1")),
                    )
                val (emitted, outcome) = runTurn(response)

                outcome.status shouldBe TurnStatus.CLARIFICATION
                outcome.pendingResumeToken shouldBe "rt-1"
                outcome.errorCode shouldBe null
                emitted.last().done.outcome shouldBe "clarification"
            }
        }

        "a clarification with no resume token is a failure, not a dead-end prompt" {
            runTest {
                val response =
                    GolemFixtures.response(
                        status = Status.STATUS_CLARIFICATION,
                        envelopes = listOf(GolemFixtures.clarificationEnvelope("")),
                    )
                val (_, outcome) = runTurn(response)

                outcome.status shouldBe TurnStatus.FAILED
                outcome.errorCode shouldBe "GOLEM_NO_RESUME_TOKEN"
            }
        }

        "a failed turn takes its code from the envelope, then Rule-6, then a default" {
            runTest {
                val fromRuleSix =
                    GolemFixtures.response(
                        status = Status.STATUS_FAILED,
                        envelopes = listOf(GolemFixtures.textEnvelope("b-1", "Nepodařilo se")),
                        messages = listOf(GolemFixtures.error("execute_failed", "query rejected")),
                    )
                run(listOf(GolemV1Event.Turn(fromRuleSix))).second.errorCode shouldBe "execute_failed"

                val bare = GolemFixtures.response(status = Status.STATUS_FAILED, envelopes = emptyList())
                run(listOf(GolemV1Event.Turn(bare))).second.errorCode shouldBe "GOLEM_TURN_FAILED"
            }
        }

        "turn-level Rule-6 messages ride on the first bubble — the only place they can render" {
            runTest {
                val response =
                    GolemFixtures.response(
                        envelopes = listOf(GolemFixtures.textEnvelope("b-1", "hm"), GolemFixtures.tableEnvelope("b-2")),
                        messages = listOf(GolemFixtures.error("partial", "jeden krok selhal")),
                    )
                val (emitted, _) = runTurn(response)
                val envelopes = emitted.filter { it.hasEnvelope() }.map { it.envelope }

                envelopes[0].messagesList.map { it.code } shouldContainExactly listOf("partial")
                envelopes[1].messagesCount shouldBe 0
            }
        }

        "current_view is stamped only onto the bubble it actually anchors" {
            runTest {
                val response =
                    GolemFixtures.response(
                        envelopes = listOf(GolemFixtures.textEnvelope("b-1", "hm"), GolemFixtures.tableEnvelope("b-2")),
                        currentViewBubble = "b-2",
                    )
                val (emitted, _) = runTurn(response)
                val envelopes = emitted.filter { it.hasEnvelope() }.map { it.envelope }

                envelopes[0].hasCurrentView() shouldBe false
                envelopes[1].currentView.patternId shouldBe "sales_by_month"
                envelopes[1].currentView.totalRows shouldBe 72L
            }
        }

        "a golem `error` frame terminates the turn FAILED with its own code" {
            runTest {
                val (emitted, outcome) = run(listOf(GolemV1Event.Error("STREAM_ERROR", "graph blew up")))

                emitted.first().hasError() shouldBe true
                emitted.first().error.code shouldBe "STREAM_ERROR"
                outcome.status shouldBe TurnStatus.FAILED
                outcome.errorCode shouldBe "STREAM_ERROR"
                emitted.last().done.outcome shouldBe "failed"
            }
        }

        "a stream that dies mid-flight still emits a terminal error and a done" {
            runTest {
                val out = mutableListOf<IrisStreamEvent>()
                val outcome =
                    GolemV1Mux().run(
                        "turn-1",
                        sessionId,
                        "golem-hartland",
                        flow {
                            emit(GolemV1Event.NodeStart("compose"))
                            throw java.io.IOException("connection reset")
                        },
                        out::add,
                    )

                out.first { it.hasError() }.error.code shouldBe "GOLEM_STREAM_ERROR"
                out.last().hasDone() shouldBe true
                outcome.status shouldBe TurnStatus.FAILED
            }
        }

        "a stream that closes with no terminal frame fails loudly rather than silently" {
            runTest {
                // The exact shape of the dispatch bug this client replaces: a well-formed
                // response with nothing in it, presented to the user as an empty answer.
                val (emitted, outcome) = run(listOf(GolemV1Event.NodeStart("compose")))

                emitted.first { it.hasError() }.error.code shouldBe "GOLEM_NO_TERMINAL_FRAME"
                outcome.status shouldBe TurnStatus.FAILED
                outcome.errorCode shouldBe "GOLEM_NO_TERMINAL_FRAME"
            }
        }

        "step details are real JSON, not interpolated strings" {
            runTest {
                val (emitted, _) = runTurn(GolemFixtures.response())
                val plan = emitted.first { it.hasStep() && it.step.node == "pick_plan" }.step
                val exec = emitted.first { it.hasStep() && it.step.node == "execute" }.step

                plan.detailJson shouldBe """{"source":"PATTERN","score":0.91}"""
                exec.detailJson shouldBe """{"rowCount":72,"durationMs":1840}"""
            }
        }
    })
