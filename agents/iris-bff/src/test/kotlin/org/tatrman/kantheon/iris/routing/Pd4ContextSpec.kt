package org.tatrman.kantheon.iris.routing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.api.ChatDispatcher
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.NewTurn
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

private fun harness(fixture: String): Triple<InMemorySessionStore, ChatDispatcher, CallerIdentity> {
    val store = InMemorySessionStore()
    val golem = FakeGolemV2Client(fixtureForQuestion = { fixture })
    val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, golem, IrisStreamMux())))
    val chat =
        ChatDispatcher(
            store,
            FakeThemisClient(),
            agents,
            InMemoryAuditStore(Ed25519Signer()),
            RoutingEnvelopes(AgentLabels.IDENTITY),
        )
    return Triple(store, chat, CallerIdentity("u1", "t1", "jwt"))
}

class Pd4ContextSpec :
    StringSpec({

        "the echoed entity_context is read back into the session" {
            runTest {
                val (store, chat, caller) = harness("entity-context.sse")
                val session = store.createSession("u1", "t1")
                val out = mutableListOf<IrisStreamEvent>()
                chat.runTurn(caller, session.sessionId, "tržby Tesco?", null, "corr", null, out::add)

                // The session now carries the agent's applied entity (c-2 / Tesco).
                store.getSession(session.sessionId)!!.entityContextJson shouldContain "c-2"
                store.getSession(session.sessionId)!!.entityContextJson shouldContain "Tesco"
            }
        }

        "PD-4 mismatch: a different applied scope appends a scope_changed WARNING" {
            runTest {
                val (store, chat, caller) = harness("entity-context.sse")
                val session = store.createSession("u1", "t1")
                // Prior in-scope context (Kaufland/c-1) + a previous turn so a handoff is assembled.
                store.setEntityContext(
                    session.sessionId,
                    """[{"entity_type":"customer","entity_id":"c-1","display_label":"Kaufland"}]""",
                )
                store.appendTurn(
                    NewTurn(
                        sessionId = session.sessionId,
                        agentId = "golem-v2",
                        question = "tržby Kaufland?",
                        status = TurnStatus.DONE,
                    ),
                )
                val out = mutableListOf<IrisStreamEvent>()
                chat.runTurn(caller, session.sessionId, "a co teď?", null, "corr", null, out::add)

                // The agent applied c-2 (Tesco) ≠ the carried c-1 (Kaufland) → warning.
                val env = out.last { it.hasEnvelope() }.envelope
                env.messagesCount shouldBe 1
                env.messagesList.single().code shouldBe "scope_changed"
                env.messagesList.single().humanMessage shouldContain "Tesco"
                env.messagesList.single().humanMessage shouldContain "Kaufland"
            }
        }

        "PD-4 match: the same applied scope adds no warning" {
            runTest {
                val (store, chat, caller) = harness("entity-context.sse")
                val session = store.createSession("u1", "t1")
                // Carried context already IS c-2 (Tesco) → the agent's echo matches.
                store.setEntityContext(
                    session.sessionId,
                    """[{"entity_type":"customer","entity_id":"c-2","display_label":"Tesco"}]""",
                )
                store.appendTurn(
                    NewTurn(
                        sessionId = session.sessionId,
                        agentId = "golem-v2",
                        question = "tržby Tesco?",
                        status = TurnStatus.DONE,
                    ),
                )
                val out = mutableListOf<IrisStreamEvent>()
                chat.runTurn(caller, session.sessionId, "a dál?", null, "corr", null, out::add)

                out.last { it.hasEnvelope() }.envelope.messagesCount shouldBe 0
            }
        }
    })
