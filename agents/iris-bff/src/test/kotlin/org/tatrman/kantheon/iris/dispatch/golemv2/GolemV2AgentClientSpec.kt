package org.tatrman.kantheon.iris.dispatch.golemv2

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.dispatch.AgentResume
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

class GolemV2AgentClientSpec :
    StringSpec({

        "runTurn creates the v2 thread once, streams the envelope, and forwards the bearer" {
            runTest {
                val store = InMemorySessionStore()
                val fake = FakeGolemV2Client()
                val agent = GolemV2AgentClient(store, fake, IrisStreamMux())
                val session = store.createSession("u1", "t1")
                val emitted = mutableListOf<IrisStreamEvent>()

                val turn =
                    AgentTurn(
                        turnId = "turn-1",
                        sessionId = session.sessionId,
                        caller = CallerIdentity("u1", "t1", "jwt-1"),
                        correlationId = "corr",
                        question = "tržby?",
                    )
                val outcome = agent.runTurn(turn, emitted::add)

                outcome.status shouldBe TurnStatus.DONE
                emitted.any { it.hasEnvelope() } shouldBe true
                emitted.first { it.hasEnvelope() }.envelope.agentId shouldBe "golem-v2"
                store.getV2Thread(session.sessionId) shouldBe session.sessionId.toString()
                fake.createdThreads.size shouldBe 1
                fake.receivedBearers.all { it == "jwt-1" } shouldBe true

                // A second turn reuses the existing thread (no second createSession).
                agent.runTurn(turn.copy(turnId = "turn-2"), {})
                fake.createdThreads.size shouldBe 1
            }
        }

        "runResume streams the resolved envelope" {
            runTest {
                val store = InMemorySessionStore()
                val agent = GolemV2AgentClient(store, FakeGolemV2Client(), IrisStreamMux())
                val session = store.createSession("u1", "t1")
                val emitted = mutableListOf<IrisStreamEvent>()

                val resume =
                    AgentResume(
                        turnId = "turn-r",
                        sessionId = session.sessionId,
                        caller = CallerIdentity("u1", "t1", "jwt"),
                        correlationId = "corr",
                        resumeToken = "rt-1",
                        selectedOptionId = "c-1",
                    )
                agent.runResume(resume, emitted::add)

                emitted.any { it.hasEnvelope() } shouldBe true
            }
        }
    })
