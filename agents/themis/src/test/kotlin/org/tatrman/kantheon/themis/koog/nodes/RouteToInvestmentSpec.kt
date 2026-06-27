package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Midas Stage 3.1 T6 — Themis routes investment portfolio questions to `golem-investment`.
 * Layer-1 entity match (PROCEDURAL on an `investment` area entity) picks the Shem with no
 * LLM call; a counter-example entity (`employee`) routes elsewhere. The registry is the
 * camelCase REST mirror an assembled golem-investment `AgentCapability` serializes to —
 * see `GolemInvestmentBundleSpec`. (Live LLM routing smoke is the demo step, not the gate.)
 */
class RouteToInvestmentSpec :
    StringSpec({

        fun caps(): CapabilitiesReadClient {
            val c = mockk<CapabilitiesReadClient>()
            coEvery { c.listAgents() } returns AGENTS_JSON
            coEvery { c.list() } returns json("""{"entries":[],"messages":[]}""")
            return c
        }

        fun input(
            entities: List<String>,
            question: String,
        ): RouteInput =
            RouteInput(
                Themis.Profile.CHAT_QUICK,
                null,
                Themis.IntentKind.PROCEDURAL,
                question,
                entities,
            )

        listOf(
            "portfolio" to "What's the YTD return on the Smith portfolio?",
            "position" to "Show me current positions for client X.",
            "transaction" to "What were the dividends paid in Q1?",
        ).forEach { (entity, question) ->
            "PROCEDURAL on the investment entity '$entity' routes to golem-investment (Layer 1, no LLM)" {
                val out = runBlocking { routeToAgent(input(listOf(entity), question), caps(), mockk()) }
                out.decision!!.chosenAgentId.value shouldBe "golem-investment"
                out.decision!!.layerHit shouldBe 1
                out.decision!!.confidence shouldBeGreaterThan 0.5
            }
        }

        "a counter-example entity ('employee') does NOT route to golem-investment" {
            val out =
                runBlocking {
                    routeToAgent(input(listOf("employee"), "Show me HR headcount."), caps(), mockk(relaxed = true))
                }
            out.decision!!.chosenAgentId.value shouldNotBe "golem-investment"
        }
    })

private fun json(literal: String): JsonObject = Json.parseToJsonElement(literal) as JsonObject

/** golem-investment alongside golem-hr — the registry shape an assembled AgentCapability serializes to. */
private val AGENTS_JSON =
    json(
        """
        {"agents":[
          {"agentKind":"AREA_QA","agentId":"golem-investment","displayName":"Investment Q&A",
           "intentKindsSupported":["PROCEDURAL"],
           "descriptionForRouter":"Investment portfolio Q&A — positions, performance, transactions, fees, FX",
           "exampleQuestions":["What's the YTD return on the Smith portfolio?","Show me current positions for client X."],
           "counterExamples":["Show me HR headcount."],
           "capabilityRefs":["midas.portfolio.performance:v1"],"areaName":"investment",
           "areaEntities":["clients","portfolios","portfolio","assets","transactions","transaction","position"],
           "nonRoutable":false},
          {"agentKind":"AREA_QA","agentId":"golem-hr","displayName":"Golem-HR",
           "intentKindsSupported":["PROCEDURAL"],"descriptionForRouter":"HR domain Q&A",
           "exampleQuestions":["list employees"],"counterExamples":["why"],
           "capabilityRefs":[],"areaName":"HR","areaEntities":["employee"],"nonRoutable":false}
        ],"messages":[]}
        """.trimIndent(),
    )
