package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Phase 3 Stage 3.3 — `routeToAgent` four-layer cascade. The capabilities client
 * returns raw JsonObject (camelCase REST mirror), stubbed here via mockk.
 */
class RouteToAgentSpec :
    StringSpec({

        fun caps(): CapabilitiesReadClient {
            val c = mockk<CapabilitiesReadClient>()
            coEvery { c.listAgents() } returns AGENTS_JSON
            coEvery { c.list() } returns LIST_JSON
            return c
        }

        "Layer 0 — routing_hint short-circuits, no LLM call" {
            val llm = mockk<LlmGatewayClient>()
            val out =
                runBlocking {
                    routeToAgent(
                        routeInput(hint = agentId("pythia"), intentKind = Themis.IntentKind.RCA),
                        caps(),
                        llm,
                    )
                }
            out.skip shouldBe false
            out.decision!!.chosenAgentId.value shouldBe "pythia"
            out.decision!!.layerHit shouldBe 0
            out.decision!!.confidence shouldBe 1.0
            coVerify(exactly = 0) { llm.complete(any(), any(), any(), any(), any()) }
        }

        "Layer 0 short-circuits before any capabilities-mcp round-trip" {
            val c = caps()
            runBlocking {
                routeToAgent(
                    routeInput(hint = agentId("pythia"), intentKind = Themis.IntentKind.RCA),
                    c,
                    mockk(),
                )
            }
            coVerify(exactly = 0) { c.list() }
            coVerify(exactly = 0) { c.listAgents() }
        }

        "no routable agents at request time → needs_user_pick with a blank chosen agent" {
            val c = mockk<CapabilitiesReadClient>()
            coEvery { c.listAgents() } returns json("""{"agents":[],"messages":[]}""")
            coEvery { c.list() } returns json("""{"entries":[],"messages":[]}""")
            // llm is never consulted — the empty registry short-circuits before Layer 2.
            val out =
                runBlocking {
                    routeToAgent(routeInput(intentKind = Themis.IntentKind.PROCEDURAL), c, mockk())
                }
            out.decision!!.needsUserPick shouldBe true
            out.decision!!.chosenAgentId.value shouldBe ""
        }

        "Layer 1 — RCA routes to the investigator with high confidence, no LLM" {
            val llm = mockk<LlmGatewayClient>()
            val out =
                runBlocking { routeToAgent(routeInput(intentKind = Themis.IntentKind.RCA), caps(), llm) }
            out.decision!!.chosenAgentId.value shouldBe "pythia"
            out.decision!!.layerHit shouldBe 1
            out.decision!!.confidence shouldBeGreaterThan 0.5
            coVerify(exactly = 0) { llm.complete(any(), any(), any(), any(), any()) }
        }

        "Layer 1 — PROCEDURAL on a single domain entity goes to golem-erp" {
            val out =
                runBlocking {
                    routeToAgent(
                        routeInput(intentKind = Themis.IntentKind.PROCEDURAL, entities = listOf("invoice")),
                        caps(),
                        mockk(),
                    )
                }
            out.decision!!.chosenAgentId.value shouldBe "golem-erp"
            out.decision!!.layerHit shouldBe 1
        }

        "Layer 2 — a tie triggers the cheap LLM and a confident verdict routes" {
            val llm = mockk<LlmGatewayClient>()
            coEvery { llm.complete(any(), any(), any(), any(), any()) } returns
                Result.success(
                    """{"chosen_agent_id":"pythia","confidence":0.82,"rationale":"cross-domain","alternates":[]}""",
                )
            // Empty entities + PROCEDURAL → pythia/golem-erp/golem-hr all score 0.5 → no clear Layer-1 winner.
            val out =
                runBlocking {
                    routeToAgent(routeInput(intentKind = Themis.IntentKind.PROCEDURAL), caps(), llm)
                }
            out.decision!!.chosenAgentId.value shouldBe "pythia"
            out.decision!!.layerHit shouldBe 2
            out.decision!!.confidence shouldBe 0.82
            coVerify(exactly = 1) { llm.complete(any(), any(), any(), any(), any()) }
        }

        "Layer 3 — low Layer-2 confidence triggers needs_user_pick with top-3 alternates" {
            val llm = mockk<LlmGatewayClient>()
            coEvery { llm.complete(any(), any(), any(), any(), any()) } returns
                Result.success(
                    """{"chosen_agent_id":"pythia","confidence":0.35,"rationale":"unsure",
                       |"alternates":[{"agent_id":"golem-erp","score":0.3,"why":"erp"},
                       |{"agent_id":"golem-hr","score":0.2,"why":"hr"}]}
                    """.trimMargin(),
                )
            val out =
                runBlocking {
                    routeToAgent(routeInput(intentKind = Themis.IntentKind.PROCEDURAL), caps(), llm)
                }
            out.decision!!.needsUserPick shouldBe true
            out.decision!!.layerHit shouldBe 3
            out.decision!!.alternatesList shouldHaveSize 3
        }

        "INVESTIGATION_DEEP profile skips routing entirely" {
            val out =
                runBlocking {
                    routeToAgent(routeInput(profile = Themis.Profile.INVESTIGATION_DEEP), caps(), mockk())
                }
            out.skip shouldBe true
            out.decision shouldBe null
        }

        "relevant_capabilities computed from intent_kind/entity tags against search_tags" {
            val out =
                runBlocking {
                    routeToAgent(
                        routeInput(intentKind = Themis.IntentKind.FORECAST, entities = listOf("invoice")),
                        caps(),
                        mockk(relaxed = true),
                    )
                }
            out.relevantCapabilities shouldContain "model.fit.arima:v1"
        }
    })

private fun agentId(value: String): AgentId = AgentId.newBuilder().setValue(value).build()

private fun routeInput(
    profile: Themis.Profile = Themis.Profile.CHAT_QUICK,
    hint: AgentId? = null,
    intentKind: Themis.IntentKind = Themis.IntentKind.PROCEDURAL,
    entities: List<String> = emptyList(),
    question: String = "test question",
): RouteInput = RouteInput(profile, hint, intentKind, question, entities)

private fun json(literal: String): JsonObject = Json.parseToJsonElement(literal) as JsonObject

private val AGENTS_JSON =
    json(
        """
        {"agents":[
          {"agentKind":"INVESTIGATOR","agentId":"pythia","displayName":"Pythia",
           "intentKindsSupported":["RCA","FORECAST","SIMULATION","PROCEDURAL"],
           "descriptionForRouter":"autonomous analytical investigator",
           "exampleQuestions":["why did revenue drop"],"counterExamples":["list invoices"],
           "capabilityRefs":["model.fit.arima:v1"],"areaEntities":[],"nonRoutable":false},
          {"agentKind":"AREA_QA","agentId":"golem-erp","displayName":"Golem-ERP",
           "intentKindsSupported":["PROCEDURAL"],"descriptionForRouter":"ERP domain Q&A",
           "exampleQuestions":["list unpaid invoices"],"counterExamples":["why"],
           "capabilityRefs":[],"areaName":"ERP","areaEntities":["customer","invoice"],"nonRoutable":false},
          {"agentKind":"AREA_QA","agentId":"golem-hr","displayName":"Golem-HR",
           "intentKindsSupported":["PROCEDURAL"],"descriptionForRouter":"HR domain Q&A",
           "exampleQuestions":["list employees"],"counterExamples":["why"],
           "capabilityRefs":[],"areaName":"HR","areaEntities":["employee"],"nonRoutable":false}
        ],"messages":[]}
        """.trimIndent(),
    )

private val LIST_JSON =
    json(
        """
        {"entries":[
          {"kind":"tool","tool":{"capabilityId":"model.fit.arima:v1","category":"model.fit.*",
           "searchTags":["forecast","model.fit","timeseries"]}},
          {"kind":"agent","agent":{"agentId":"pythia"}}
        ],"messages":[]}
        """.trimIndent(),
    )
