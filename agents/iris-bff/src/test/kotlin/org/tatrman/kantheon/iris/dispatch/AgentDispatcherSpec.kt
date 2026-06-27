package org.tatrman.kantheon.iris.dispatch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.common.v1.HandoffContext
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import java.util.UUID

/** Records the turn it was handed and replays a canned DONE outcome. */
private class RecordingAgentClient : AgentClient {
    var lastTurn: AgentTurn? = null
    var lastResume: AgentResume? = null

    override suspend fun runTurn(
        turn: AgentTurn,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        lastTurn = turn
        return TurnOutcome(null, TurnStatus.DONE, null, null, "done")
    }

    override suspend fun runResume(
        resume: AgentResume,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        lastResume = resume
        return TurnOutcome(null, TurnStatus.DONE, null, null, "done")
    }
}

private fun caller() = CallerIdentity("u1", "t1", "jwt")

private fun turn(
    agentHandoff: HandoffContext? = null,
    turnId: String = "turn-1",
) = AgentTurn(
    turnId = turnId,
    sessionId = UUID.randomUUID(),
    caller = caller(),
    correlationId = "corr-1",
    question = "tržby?",
    handoff = agentHandoff,
)

class AgentDispatcherSpec :
    StringSpec({

        "dispatches a known agent id to its client and returns its outcome" {
            runTest {
                val golem = RecordingAgentClient()
                val dispatcher = AgentDispatcher(mapOf("golem-v2" to golem))
                val emitted = mutableListOf<IrisStreamEvent>()

                val outcome = dispatcher.dispatch("golem-v2", turn(), emitted::add)

                outcome.status shouldBe TurnStatus.DONE
                golem.lastTurn.shouldNotBeNull()
            }
        }

        "forwards the assembled handoff to the client unchanged" {
            runTest {
                val golem = RecordingAgentClient()
                val dispatcher = AgentDispatcher(mapOf("golem-v2" to golem))
                val handoff =
                    HandoffContext
                        .newBuilder()
                        .setSourceAgentId("golem-erp")
                        .setUserQuestion("q")
                        .build()

                dispatcher.dispatch("golem-v2", turn(agentHandoff = handoff), {})

                golem.lastTurn!!.handoff shouldBe handoff
            }
        }

        "unknown agent id emits a NO_AGENT_CLIENT error tail and a FAILED outcome" {
            runTest {
                val dispatcher = AgentDispatcher(mapOf("golem-v2" to RecordingAgentClient()))
                val emitted = mutableListOf<IrisStreamEvent>()

                val outcome = dispatcher.dispatch("pythia", turn(), emitted::add)

                outcome.status shouldBe TurnStatus.FAILED
                outcome.errorCode shouldBe "NO_AGENT_CLIENT"
                emitted.first { it.hasError() }.error.code shouldBe "NO_AGENT_CLIENT"
                emitted.last().hasDone() shouldBe true
                emitted.last().done.outcome shouldBe "failed"
                // sequence is well-formed (1, 2).
                emitted.map { it.sequence } shouldBe listOf(1L, 2L)
            }
        }

        "supports() reflects the registered map" {
            val dispatcher = AgentDispatcher(mapOf("golem-v2" to RecordingAgentClient()))
            dispatcher.supports("golem-v2") shouldBe true
            dispatcher.supports("pythia") shouldBe false
        }

        "resume routes to the issuing agent's client" {
            runTest {
                val golem = RecordingAgentClient()
                val dispatcher = AgentDispatcher(mapOf("golem-v2" to golem))
                val resume =
                    AgentResume("turn-r", UUID.randomUUID(), caller(), "corr", "rt-1", selectedOptionId = "c-1")

                dispatcher.resume("golem-v2", resume, {})

                golem.lastResume!!.resumeToken shouldBe "rt-1"
            }
        }

        "resume to an unknown issuer emits NO_AGENT_CLIENT" {
            runTest {
                val dispatcher = AgentDispatcher(emptyMap())
                val emitted = mutableListOf<IrisStreamEvent>()
                val resume = AgentResume("turn-r", UUID.randomUUID(), caller(), "corr", "rt-1")

                val outcome = dispatcher.resume("ghost", resume, emitted::add)

                outcome.errorCode shouldBe "NO_AGENT_CLIENT"
                emitted.first { it.hasError() }.error.code shouldBe "NO_AGENT_CLIENT"
            }
        }
    })
