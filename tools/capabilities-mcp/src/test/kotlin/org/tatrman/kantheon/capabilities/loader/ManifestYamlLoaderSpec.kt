package org.tatrman.kantheon.capabilities.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.capabilities.registry.InMemoryRegistry
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.IntentKind

class ManifestYamlLoaderSpec :
    StringSpec({

        "parseAgent reads a valid AgentCapability YAML" {
            val yaml =
                """
                agent_kind: INVESTIGATOR
                agent_id: pythia
                display_name: Pythia
                intent_kinds_supported: [RCA, FORECAST]
                description_for_router: "Analytical investigator"
                example_questions: ["Why are sales down?"]
                counter_examples: []
                capability_refs: []
                service_endpoint: "http://pythia.kantheon.svc.cluster.local:7301"
                health_check_path: /health
                typical_latency_ms: 30000
                typical_cost_usd: 0.15
                hitl_default: INTERACTIVE
                """.trimIndent()
            val cap = ManifestYamlLoader.parseAgent(yaml)
            cap.agentKind shouldBe AgentKind.INVESTIGATOR
            cap.agentId shouldBe "pythia"
            cap.intentKindsSupportedList shouldContainExactlyInAnyOrder listOf(IntentKind.RCA, IntentKind.FORECAST)
            cap.serviceEndpoint shouldBe "http://pythia.kantheon.svc.cluster.local:7301"
            cap.typicalLatencyMs shouldBe 30_000
        }

        "parseAgent reads non_routable + visibility_roles (Hebe P3 S3.4)" {
            val yaml =
                """
                agent_kind: PERSONAL_ASSISTANT
                agent_id: hebe-bora
                display_name: "Hebe (Bora)"
                non_routable: true
                visibility_roles: ["kantheon-area-hr"]
                """.trimIndent()
            val cap = ManifestYamlLoader.parseAgent(yaml)
            cap.agentKind shouldBe AgentKind.PERSONAL_ASSISTANT
            cap.nonRoutable shouldBe true
            cap.visibilityRolesList shouldContain "kantheon-area-hr"
        }

        "parseAgent defaults non_routable to false and visibility_roles to empty" {
            val cap =
                ManifestYamlLoader.parseAgent(
                    """
                    agent_kind: AREA_QA
                    agent_id: golem-erp
                    """.trimIndent(),
                )
            cap.nonRoutable shouldBe false
            cap.visibilityRolesList.shouldHaveSize(0)
        }

        "parseAgent reads a ShemManifest YAML with ERP fields" {
            val yaml =
                """
                agent_kind: AREA_QA
                agent_id: golem-erp
                display_name: Golem-ERP
                intent_kinds_supported: [PROCEDURAL]
                description_for_router: "ERP domain Q&A"
                example_questions: ["Které faktury Shell ještě neuhradil?"]
                counter_examples: ["Proč klesly tržby Castrolu?"]
                capability_refs: ["theseus.query:v1", "render.table:v1"]
                service_endpoint: "http://golem-erp.kantheon.svc.cluster.local:7401"
                health_check_path: /health
                typical_latency_ms: 5000
                typical_cost_usd: 0.02
                hitl_default: INTERACTIVE
                area_name: ERP
                area_entities: [customer, invoice]
                preferred_queries: [listUnpaidInvoices]
                preferred_capabilities: [theseus.query:v1]
                style_addendum: "Czech responses default to formal."
                locale_defaults:
                  - locale: cs-CZ
                    greeting: "Dobrý den, jak vám mohu pomoci?"
                    date_format: "dd.MM.yyyy"
                    currency: "CZK"
                """.trimIndent()
            val cap = ManifestYamlLoader.parseAgent(yaml)
            cap.agentKind shouldBe AgentKind.AREA_QA
            cap.areaName shouldBe "ERP"
            cap.areaEntitiesList shouldContainExactlyInAnyOrder listOf("customer", "invoice")
            cap.localeDefaultsList.first().locale shouldBe "cs-CZ"
            cap.localeDefaultsList.first().currency shouldBe "CZK"
        }

        "parseTool reads a valid ToolCapability YAML" {
            val yaml =
                """
                capability_id: theseus.query:v1
                category: theseus.*
                version: "v1"
                search_tags: [sql, named-query]
                service_endpoint: "http://theseus-mcp.kantheon.svc.cluster.local:7307"
                description: "Executes a parameterised named query."
                cost_hints:
                  typical_latency_ms: 800
                  typical_cost_usd: 0.001
                  is_idempotent: true
                  max_concurrent: 50
                """.trimIndent()
            val cap = ManifestYamlLoader.parseTool(yaml)
            cap.capabilityId shouldBe "theseus.query:v1"
            cap.category shouldBe "theseus.*"
            cap.searchTagsList shouldContain "sql"
            cap.costHints.typicalLatencyMs shouldBe 800.0
            cap.costHints.isIdempotent shouldBe true
        }

        "loadAll discovers agents/*.yaml and tools/*.yaml under a classpath root" {
            val reg = InMemoryRegistry()
            val loader = ManifestYamlLoader(classpathBase = "/manifests-classpath")
            val report = loader.loadAll(reg)
            report.loaded shouldBe 3
            report.skipped shouldHaveSize 0
            reg
                .get("pythia-test")
                ?.capability
                ?.agent
                ?.agentKind shouldBe AgentKind.INVESTIGATOR
            reg
                .get("golem-erp-test")
                ?.capability
                ?.agent
                ?.areaName shouldBe "ERP"
            reg
                .get("theseus.query:v1")
                ?.capability
                ?.tool
                ?.category shouldBe "theseus.*"
        }

        "fixture-loaded entries carry no last_heartbeat_at (exempt from TTL pruner)" {
            val reg = InMemoryRegistry()
            val loader = ManifestYamlLoader(classpathBase = "/manifests-classpath")
            loader.loadAll(reg)
            reg.get("pythia-test")?.lastHeartbeatAt.shouldBeNull()
        }

        "invalid YAML logs + skips without crashing" {
            val reg = InMemoryRegistry()
            val loader = ManifestYamlLoader(classpathBase = "/manifests-invalid")
            val report = loader.loadAll(reg)
            report.loaded shouldBe 0
            report.skipped shouldHaveSize 1
            report.skipped
                .first()
                .reason
                .shouldNotBeNull()
            report.skipped.first().reason shouldContain "agent_kind"
            reg.list() shouldHaveSize 0
        }
    })
