package org.tatrman.kantheon.iris.discover

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private fun agents(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class DiscoverServiceSpec :
    StringSpec({

        "maps routable agents to domain cards (camelCase proto-JSON)" {
            val json =
                agents(
                    """
                    {"agents":[
                      {"agentId":"golem-erp","displayName":"ERP","descriptionForRouter":"Accounting & invoices",
                       "exampleQuestions":["Revenue by month?","Top customers?"]}
                    ]}
                    """.trimIndent(),
                )
            val cards = DiscoverService.build(json, emptySet())
            cards.size shouldBe 1
            cards[0].agentId shouldBe "golem-erp"
            cards[0].displayName shouldBe "ERP"
            cards[0].blurb shouldBe "Accounting & invoices"
            cards[0].exampleQuestions shouldContainExactly listOf("Revenue by month?", "Top customers?")
        }

        "excludes non_routable agents" {
            val json =
                agents("""{"agents":[{"agentId":"themis","displayName":"Themis","nonRoutable":true}]}""")
            DiscoverService.build(json, emptySet()) shouldBe emptyList()
        }

        "an agent with visibility_roles is hidden unless the caller holds one" {
            val json =
                agents(
                    """{"agents":[{"agentId":"golem-hr","displayName":"HR","visibilityRoles":["hr-viewer"]}]}""",
                )
            DiscoverService.build(json, emptySet()) shouldBe emptyList()
            DiscoverService.build(json, setOf("hr-viewer")).map { it.agentId } shouldContainExactly listOf("golem-hr")
        }

        "an agent with no visibility_roles is public" {
            val json = agents("""{"agents":[{"agentId":"golem-erp","displayName":"ERP"}]}""")
            DiscoverService.build(json, emptySet()).size shouldBe 1
        }

        "tolerates snake_case + a nested manifest object" {
            val json =
                agents(
                    """{"agents":[{"agent_id":"golem-sales","manifest":{"display_name":"Sales","example_questions":["Q1?"]}}]}""",
                )
            val cards = DiscoverService.build(json, emptySet())
            cards[0].agentId shouldBe "golem-sales"
            cards[0].displayName shouldBe "Sales"
            cards[0].exampleQuestions shouldContainExactly listOf("Q1?")
        }

        "empty / missing agents array yields no cards" {
            DiscoverService.build(agents("""{}"""), emptySet()) shouldBe emptyList()
        }
    })
