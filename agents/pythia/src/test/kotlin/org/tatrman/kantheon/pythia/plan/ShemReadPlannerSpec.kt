package org.tatrman.kantheon.pythia.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/** Two ShemManifests (ERP + HR) as the capabilities-mcp `/v1/capabilities/agents` payload. */
internal val TWO_AREAS: JsonObject =
    Json.parseToJsonElement(
        """
        { "agents": [
          { "agent_id": "golem-erp", "agent_kind": "AREA_QA", "area_name": "ERP",
            "area_entities": ["customer", "invoice", "product"],
            "area_terminology": [ { "term": "objednávka", "definition": "sales_order nebo purchase_order" } ],
            "preferred_queries": ["listUnpaidInvoices", "listOrdersByCustomer"],
            "capability_refs": ["query.query:v1"] },
          { "agent_id": "golem-hr", "agent_kind": "AREA_QA", "area_name": "HR",
            "area_entities": ["employee", "wage", "cost_center"],
            "area_terminology": [ { "term": "mzda", "definition": "gross wage cost on a cost center" } ],
            "preferred_queries": ["wageByCostCenter"],
            "capability_refs": ["query.query:v1"] },
          { "agent_id": "pythia", "agent_kind": "INVESTIGATOR", "area_entities": [] }
        ] }
        """.trimIndent(),
    ) as JsonObject

/**
 * Stage 5.1 T1 — master-of-Golems Shem reads. The planner pulls the relevant Golems'
 * Shem context (relevance = `area_entities` ∩ resolved entities): a cross-area
 * question pulls both Shems; an in-area question pulls one; the registry only ever
 * yields manifests (no agent-to-agent calls — R4).
 */
class ShemReadPlannerSpec :
    StringSpec({

        val reader = ShemReader { TWO_AREAS }

        "a cross-area question (customer + employee) pulls both Shems' context" {
            runTest {
                val shems = reader.relevantShems(listOf("customer", "employee"))
                shems.map { it.areaName }.sorted() shouldBe listOf("ERP", "HR")
                val rendered = reader.render(shems)
                rendered shouldContain "listUnpaidInvoices"
                rendered shouldContain "wageByCostCenter"
                rendered shouldContain "objednávka"
                rendered shouldContain "mzda"
            }
        }

        "an in-area question (invoice) pulls only the matching Shem" {
            runTest {
                val shems = reader.relevantShems(listOf("invoice"))
                shems shouldHaveSize 1
                shems.single().areaName shouldBe "ERP"
                shems.single().preferredQueries shouldContain "listUnpaidInvoices"
            }
        }

        "no resolved entities → no Shem context (and an INVESTIGATOR agent is never a Shem)" {
            runTest {
                reader.relevantShems(emptyList()) shouldHaveSize 0
                reader.relevantShems(listOf("pythia-only-thing")) shouldHaveSize 0
            }
        }
    })
