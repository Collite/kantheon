package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.routing.AgentLabels
import org.tatrman.kantheon.iris.routing.FakeThemisClient
import org.tatrman.kantheon.iris.routing.RoutingEnvelopes
import org.tatrman.kantheon.iris.routing.ThemisClient
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.themis.v1.Themis.GapKind

private fun dispatcher(
    store: InMemorySessionStore,
    themis: ThemisClient,
    metrics: RoutingMetrics,
): ChatDispatcher {
    val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, FakeGolemV2Client(), IrisStreamMux())))
    return ChatDispatcher(
        store,
        themis,
        agents,
        InMemoryAuditStore(Ed25519Signer()),
        RoutingEnvelopes(AgentLabels.IDENTITY),
        metrics = metrics,
    )
}

class ObservabilitySpec :
    StringSpec({

        "a dispatched turn records the iris_turn_duration timer with the outcome tag" {
            runTest {
                val registry = SimpleMeterRegistry()
                val store = InMemorySessionStore()
                val chat = dispatcher(store, FakeThemisClient(), RoutingMetrics(registry))
                val s = store.createSession("u1", "t1")
                chat.runTurn(CallerIdentity("u1", "t1", "j"), s.sessionId, "q", null, "c", null) {}

                registry.timer("iris_turn_duration", "outcome", "done").count() shouldBe 1L
            }
        }

        "needs_user_pick increments the pick-rate counter" {
            runTest {
                val registry = SimpleMeterRegistry()
                val store = InMemorySessionStore()
                val themis = FakeThemisClient(responder = { FakeThemisClient.needsUserPick(listOf("pythia" to "w")) })
                val chat = dispatcher(store, themis, RoutingMetrics(registry))
                val s = store.createSession("u1", "t1")
                chat.runTurn(CallerIdentity("u1", "t1", "j"), s.sessionId, "q", null, "c", null) {}

                registry.counter("iris_routing_needs_user_pick_total").count() shouldBe 1.0
            }
        }

        "a refusal increments the refusal counter tagged by gap code" {
            runTest {
                val registry = SimpleMeterRegistry()
                val store = InMemorySessionStore()
                val themis =
                    FakeThemisClient(
                        responder = { FakeThemisClient.refusal(listOf(GapKind.NO_ENTITLED_AGENT to "denied")) },
                    )
                val chat = dispatcher(store, themis, RoutingMetrics(registry))
                val s = store.createSession("u1", "t1")
                chat.runTurn(CallerIdentity("u1", "t1", "j"), s.sessionId, "q", null, "c", null) {}

                registry.counter("iris_routing_refusal_total", "code", "NO_ENTITLED_AGENT").count() shouldBe 1.0
            }
        }

        "load sanity: 20 concurrent turns all complete without contention" {
            runTest {
                val store = InMemorySessionStore()
                val chat = dispatcher(store, FakeThemisClient(), RoutingMetrics(SimpleMeterRegistry()))
                val sessions = (1..20).map { store.createSession("u$it", "t1") }

                val outcomes =
                    coroutineScope {
                        sessions
                            .map { s ->
                                async {
                                    val sink = mutableListOf<IrisStreamEvent>()
                                    chat.runTurn(
                                        CallerIdentity(s.userId, "t1", "j"),
                                        s.sessionId,
                                        "q",
                                        null,
                                        "c",
                                        null,
                                    ) {
                                        sink.add(it)
                                    }
                                }
                            }.awaitAll()
                    }

                outcomes.all { it.status == TurnStatus.DONE } shouldBe true
                outcomes.size shouldBe 20
            }
        }
    })
