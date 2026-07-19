package org.tatrman.kantheon.golem.shem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.capabilities.v1.IntentKind

/**
 * Unit contract for [ShemAssembler] — the pure four-source assembly (golem/contracts
 * §6) WITHOUT gRPC: a parsed overlay + the resolved-area responses + a fixture model
 * → an `AgentCapability` (`agent_kind == AREA_QA`). Asserts each field's provenance
 * per the assembly table.
 */
class ShemAssemblySpec :
    StringSpec({

        val overlay = ShemOverlayParser.parse(VALID_OVERLAY_YAML)
        val areas = listOf(resolveArea())
        val model = fixtureModel()

        "identity + template constants come from the overlay source + template" {
            val cap = ShemAssembler.assemble(overlay, areas, model, "cs")
            cap.agentId shouldBe "golem-ucetnictvi"
            cap.displayName shouldBe "Účetnictví"
            cap.areaName shouldBe "accounting"
            cap.agentKind shouldBe AgentKind.AREA_QA
            cap.intentKindsSupportedList shouldContainExactly listOf(IntentKind.PROCEDURAL)
            cap.hitlDefault shouldBe HitlProfile.INTERACTIVE
            cap.serviceEndpoint shouldBe "http://golem-ucetnictvi.kantheon.svc.cluster.local:7420"
            cap.healthCheckPath shouldBe "/health"
            cap.capabilityRefsList shouldContainExactly
                listOf("query.query:v1", "query.compile:v1", "render.table:v1", "render.chart:v1")
            cap.preferredCapabilitiesList shouldContainExactly cap.capabilityRefsList
            cap.typicalLatencyMs shouldBe 0
            cap.typicalCostUsd shouldBe 0.0
        }

        "area_entities + preferred_queries are model-derived (entity / pattern-query localNames)" {
            val cap = ShemAssembler.assemble(overlay, areas, model, "cs")
            cap.areaEntitiesList shouldContainExactly listOf("ucet", "obdobi", "hodnota")
            cap.preferredQueriesList shouldContainExactly listOf("zustatkyUctu", "nezauctovaneDoklady")
        }

        "area_terminology is best-effort — one TermDef per entity with a description or aliases" {
            val cap = ShemAssembler.assemble(overlay, areas, model, "cs")
            // "hodnota" has neither description nor aliases → skipped.
            cap.areaTerminologyList.map { it.term } shouldContainExactly listOf("ucet", "obdobi")
            val ucet = cap.areaTerminologyList.single { it.term == "ucet" }
            ucet.definition shouldBe "Účetní účet"
            ucet.synonymsList shouldContainExactly listOf("account", "konto")
            val obdobi = cap.areaTerminologyList.single { it.term == "obdobi" }
            obdobi.definition shouldBe "Účetní období"
            obdobi.synonymsList.shouldBeEmpty()
        }

        "visibility_roles + the overlay router seed + examples are carried from the overlay" {
            val cap = ShemAssembler.assemble(overlay, areas, model, "cs")
            cap.visibilityRolesList shouldContainExactly listOf("kantheon-area-accounting")
            cap.descriptionForRouter shouldContain "Účetnictví a navazující"
            cap.exampleQuestionsList.single() shouldContain "4902"
            cap.counterExamplesList.single() shouldContain "marže"
            cap.localeDefaultsList.single().currency shouldBe "CZK"
        }

        "description_for_router is seeded from the area(s) when the overlay omits it" {
            val minimal = ShemOverlayParser.parse(MINIMAL_OVERLAY_YAML)
            val cap = ShemAssembler.assemble(minimal, areas, model, "cs")
            // seeded: "<area description> (<tags>)"
            cap.descriptionForRouter shouldBe "Účetnictví a navazující obchodní doklady (finance)"
        }

        "locale_defaults fall back to the template defaults when the overlay omits them" {
            val minimal = ShemOverlayParser.parse(MINIMAL_OVERLAY_YAML)
            val cap = ShemAssembler.assemble(minimal, areas, model, "cs")
            cap.localeDefaultsList.map { it.locale } shouldContainExactly listOf("cs-CZ", "en")
            cap.localeDefaultsList.single { it.locale == "en" }.currency shouldBe "EUR"
        }

        "multiple areas join area_name by ',' and seed description from each" {
            val twoAreaOverlay =
                ShemOverlayParser.parse(
                    MINIMAL_OVERLAY_YAML.replace("areas: [accounting]", "areas: [accounting, sales]"),
                )
            val twoResults =
                listOf(
                    resolveArea(description = "Accounting", tags = listOf("finance")),
                    resolveArea(description = "Sales", tags = emptyList()),
                )
            val cap = ShemAssembler.assemble(twoAreaOverlay, twoResults, model, "cs")
            cap.areaName shouldBe "accounting,sales"
            cap.descriptionForRouter shouldBe "Accounting (finance) Sales"
        }
    })
