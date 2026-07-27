package org.tatrman.kantheon.iris.dispatch.golem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

class GolemV1AgentClientSpec :
    StringSpec({

        "runTurn dispatches one self-contained call and forwards the caller's bearer" {
            runTest {
                val store = InMemorySessionStore()
                val fake = FakeGolemV1Client()
                val agent = GolemV1AgentClient("golem-hartland", store, fake)
                val session = store.createSession("maya", "hartland")
                val emitted = mutableListOf<IrisStreamEvent>()

                val outcome =
                    agent.runTurn(
                        AgentTurn(
                            turnId = "turn-1",
                            sessionId = session.sessionId,
                            caller = CallerIdentity("maya", "hartland", "jwt-1"),
                            correlationId = "corr-9",
                            question = "tržby?",
                        ),
                        emitted::add,
                    )

                outcome.status shouldBe TurnStatus.DONE
                emitted.first { it.hasEnvelope() }.envelope.agentId shouldBe "golem-hartland"
                fake.bearers.single() shouldBe "jwt-1"
                fake.correlationIds.single() shouldBe "corr-9"
                fake.requests.single().golemId shouldBe "golem-hartland"
                // No session/thread handshake exists on this surface — one call, one turn.
                store.getV2Thread(session.sessionId) shouldBe null
            }
        }

        "the conversation excerpt is read from the session store, not threaded through AgentTurn" {
            runTest {
                val store = InMemorySessionStore()
                val fake = FakeGolemV1Client()
                val agent = GolemV1AgentClient("golem-hartland", store, fake)
                val session = store.createSession("maya", "hartland")
                store.appendTurn(
                    NewTurn(
                        sessionId = session.sessionId,
                        agentId = "golem-hartland",
                        question = "a co loni?",
                        status = TurnStatus.DONE,
                    ),
                )

                agent.runTurn(
                    AgentTurn(
                        turnId = "turn-2",
                        sessionId = session.sessionId,
                        caller = CallerIdentity("maya", "hartland", "jwt-1"),
                        correlationId = "corr-9",
                        question = "tržby?",
                    ),
                    {},
                )

                fake.requests
                    .single()
                    .context.conversationExcerptList
                    .single()
                    .question shouldBe "a co loni?"
            }
        }

        "runResume forwards the token golem minted and streams the resolved turn" {
            runTest {
                val store = InMemorySessionStore()
                val fake = FakeGolemV1Client()
                val agent = GolemV1AgentClient("golem-hartland", store, fake)
                val session = store.createSession("maya", "hartland")
                val emitted = mutableListOf<IrisStreamEvent>()

                val outcome =
                    agent.runResume(
                        AgentResume(
                            turnId = "turn-3",
                            sessionId = session.sessionId,
                            caller = CallerIdentity("maya", "hartland", "jwt-1"),
                            correlationId = "corr-9",
                            resumeToken = "rt-1",
                            selectedOptionId = "opt-a",
                            freeTextAnswer = null,
                        ),
                        emitted::add,
                    )

                outcome.status shouldBe TurnStatus.DONE
                fake.resumes.single().resumeToken shouldBe "rt-1"
                fake.resumes.single().selectedOptionId shouldBe "opt-a"
                fake.bearers.single() shouldBe "jwt-1"
                emitted.last().hasDone() shouldBe true
            }
        }
    })
