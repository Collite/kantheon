package org.tatrman.kantheon.iris.routing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.iris.api.CallerIdentity
import org.tatrman.kantheon.iris.api.ChatDispatcher
import org.tatrman.kantheon.iris.audit.Ed25519Signer
import org.tatrman.kantheon.iris.audit.InMemoryAuditStore
import org.tatrman.kantheon.iris.dispatch.AgentDispatcher
import org.tatrman.kantheon.iris.dispatch.golemv2.FakeGolemV2Client
import org.tatrman.kantheon.iris.dispatch.golemv2.GolemV2AgentClient
import org.tatrman.kantheon.iris.domain.InMemorySessionStore
import org.tatrman.kantheon.iris.domain.SessionStore
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.IrisStreamMux
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.themis.v1.Themis.Decomposition
import org.tatrman.kantheon.themis.v1.Themis.GapKind

private val auditJson = Json { ignoreUnknownKeys = true }

private class Harness(
    themis: ThemisClient,
    private val golem: FakeGolemV2Client = FakeGolemV2Client(),
) {
    val store: SessionStore = InMemorySessionStore()
    val audit = InMemoryAuditStore(Ed25519Signer())
    private val agents = AgentDispatcher(mapOf("golem-v2" to GolemV2AgentClient(store, golem, IrisStreamMux())))
    val dispatcher = ChatDispatcher(store, themis, agents, audit, RoutingEnvelopes(AgentLabels.IDENTITY))
    val session = store.createSession("u1", "t1")
    val caller = CallerIdentity("u1", "t1", "jwt")

    suspend fun turn(
        question: String,
        routingHint: String? = null,
    ): List<IrisStreamEvent> {
        val out = mutableListOf<IrisStreamEvent>()
        dispatcher.runTurn(caller, session.sessionId, question, null, "corr", routingHint, out::add)
        return out
    }

    fun turns() = store.getTurns(session.sessionId, includeDiscarded = true)

    fun dispatchedThreadCount() = golem.createdThreads.size
}

class ThemisDispatchSpec :
    StringSpec({

        "Resolution → dispatch to chosen agent; one envelope; turn agent_id set; audit carries routing" {
            runTest {
                val h = Harness(FakeThemisClient(responder = { FakeThemisClient.resolutionTo("golem-v2", layer = 1) }))
                val emitted = h.turn("kolik tržeb?")

                emitted.count { it.hasEnvelope() } shouldBe 1
                emitted.last().hasDone() shouldBe true
                val t = h.turns().single()
                t.agentId shouldBe "golem-v2"
                t.status shouldBe TurnStatus.DONE

                val payload =
                    auditJson
                        .parseToJsonElement(
                            h.audit
                                .all()
                                .first { it.eventKind == "turn" }
                                .payloadJson,
                        ).jsonObject
                payload["routingChosenAgentId"]!!.jsonPrimitive.content shouldBe "golem-v2"
                payload["routingLayerHit"]!!.jsonPrimitive.content shouldBe "1"
                payload.containsKey("routingConfidence") shouldBe true
            }
        }

        "needs_user_pick → RoutingPickChips, no dispatch, alternates persisted" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(
                            responder = {
                                FakeThemisClient.needsUserPick(
                                    listOf("pythia" to "deep analysis", "golem-erp" to "ERP facts"),
                                )
                            },
                        ),
                    )
                val emitted = h.turn("proč klesly tržby a co s tím?")

                h.dispatchedThreadCount() shouldBe 0
                val env = emitted.single { it.hasEnvelope() }.envelope
                env.chipsList.size shouldBe 2
                env.chipsList.all { it.hasRouting() } shouldBe true
                env.chipsList.map { it.routing.agentId.value } shouldContainExactly listOf("pythia", "golem-erp")
                env.chipsList[0].routing.why shouldBe "deep analysis"
                h.turns().single().alternatesOffered shouldContainExactly listOf("pythia", "golem-erp")
            }
        }

        "RoutingPickChip click: routing_hint reaches Themis → Layer-0 dispatch to picked agent" {
            runTest {
                val fake = FakeThemisClient() // default honours routing_hint as Layer-0
                val h = Harness(fake)

                h.turn("which one?", routingHint = "golem-v2")

                fake.seenRequests.last().hasRoutingHint() shouldBe true
                fake.seenRequests
                    .last()
                    .routingHint.value shouldBe "golem-v2"
                h.dispatchedThreadCount() shouldBe 1
                h.turns().last().agentId shouldBe "golem-v2"
            }
        }

        "multi_question SPLIT → one PromptChip per sub-question, no dispatch" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(
                            responder = {
                                FakeThemisClient.multiQuestion(
                                    listOf("Kolik tržeb?", "Kolik nákladů?"),
                                    Decomposition.SPLIT,
                                )
                            },
                        ),
                    )
                val emitted = h.turn("tržby a náklady?")

                h.dispatchedThreadCount() shouldBe 0
                val env = emitted.single { it.hasEnvelope() }.envelope
                env.chipsList.size shouldBe 2
                env.chipsList.all { it.hasPrompt() } shouldBe true
                env.chipsList.map { it.prompt.prompt } shouldContainExactly listOf("Kolik tržeb?", "Kolik nákladů?")
            }
        }

        "multi_question KEEP_TOGETHER → single dispatch + rationale hint bubble" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(
                            responder = {
                                FakeThemisClient.multiQuestion(
                                    listOf("a", "b"),
                                    Decomposition.KEEP_TOGETHER,
                                    rationale = "These relate; answering together.",
                                )
                            },
                        ),
                    )
                val emitted = h.turn("how does a affect b?")

                h.dispatchedThreadCount() shouldBe 1
                emitted.any { it.hasEnvelope() && it.envelope.text == "These relate; answering together." } shouldBe
                    true
                emitted.count { it.hasEnvelope() } shouldBe 2
                // One monotone sequence across the hint + the dispatch.
                emitted.map { it.sequence } shouldContainExactly (1L..emitted.size.toLong()).toList()
            }
        }

        "RefusalWithGaps[NO_ENTITLED_AGENT] → error envelope, no dispatch" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(
                            responder = {
                                FakeThemisClient.refusal(
                                    listOf(
                                        GapKind.NO_ENTITLED_AGENT to "The HR domain exists but you cannot access it.",
                                    ),
                                    rationale = "No agent you can use can answer this.",
                                )
                            },
                        ),
                    )
                val emitted = h.turn("show me HR salaries")

                h.dispatchedThreadCount() shouldBe 0
                val env = emitted.single { it.hasEnvelope() }.envelope
                env.errorCode shouldBe "NO_ENTITLED_AGENT"
                env.text shouldBe
                    "No agent you can use can answer this.\n• The HR domain exists but you cannot access it."
                h.turns().single().status shouldBe TurnStatus.FAILED
                emitted.last().done.outcome shouldBe "failed"
            }
        }

        "RefusalWithGaps with multiple gaps lists each" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(
                            responder = {
                                FakeThemisClient.refusal(
                                    listOf(
                                        GapKind.CAPABILITY_UNAVAILABLE to "No forecasting capability is registered.",
                                        GapKind.OUT_OF_DATA_SCOPE to "Data ends in 2024.",
                                    ),
                                )
                            },
                        ),
                    )
                val emitted = h.turn("forecast 2030")

                val env = emitted.single { it.hasEnvelope() }.envelope
                env.errorCode shouldBe "CAPABILITY_UNAVAILABLE"
                env.text.lines().size shouldBe 2
            }
        }

        "Themis unreachable → THEMIS_UNAVAILABLE error envelope, FAILED turn" {
            runTest {
                val h =
                    Harness(
                        FakeThemisClient(responder = { throw ThemisUnavailableException("down") }),
                    )
                val emitted = h.turn("anything")

                emitted.single { it.hasEnvelope() }.envelope.errorCode shouldBe "THEMIS_UNAVAILABLE"
                h.turns().single().status shouldBe TurnStatus.FAILED
            }
        }

        "every dispatch forwards the caller bearer to Themis" {
            runTest {
                val fake = FakeThemisClient()
                val h = Harness(fake)
                h.turn("q")
                fake.seenBearers.last() shouldBe "jwt"
            }
        }
    })
