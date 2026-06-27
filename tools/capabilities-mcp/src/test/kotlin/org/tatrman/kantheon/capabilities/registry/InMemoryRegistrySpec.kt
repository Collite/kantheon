package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.tatrman.kantheon.capabilities.agentCapability
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.toolCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InMemoryRegistrySpec :
    StringSpec({

        val fixedClock = Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC)
        lateinit var reg: InMemoryRegistry

        beforeTest {
            reg = InMemoryRegistry(clock = fixedClock)
        }

        "register tool returns a stable registration_id" {
            val rid = reg.register(toolCapability("model.fit.arima:v1").asCapability())
            rid.shouldNotBeBlank()
            val entry = reg.get("model.fit.arima:v1")
            entry.shouldNotBeNull()
            entry.capability.hasTool() shouldBe true
            entry.capability.tool.capabilityId shouldBe "model.fit.arima:v1"
        }

        "register is idempotent on capability_id (same id → same registration_id)" {
            val rid1 = reg.register(toolCapability("model.fit.arima:v1", description = "initial").asCapability())
            val rid2 = reg.register(toolCapability("model.fit.arima:v1", description = "updated").asCapability())
            rid1 shouldBe rid2
            reg
                .get("model.fit.arima:v1")
                ?.capability
                ?.tool
                ?.description shouldBe "updated"
        }

        "list returns all entries; listAgents returns only agent entries" {
            reg.register(toolCapability("model.fit.arima:v1").asCapability())
            reg.register(agentCapability("pythia", AgentKind.INVESTIGATOR).asCapability())
            reg.register(agentCapability("golem-erp", AgentKind.AREA_QA).asCapability())

            reg.list() shouldHaveSize 3
            reg.listAgents().map { it.agentId } shouldContainExactlyInAnyOrder listOf("pythia", "golem-erp")
        }

        "get returns null for unknown id" {
            reg.get("unknown") shouldBe null
        }

        "register an AgentCapability with ShemManifest fields preserves them" {
            val golem =
                agentCapability("golem-erp", AgentKind.AREA_QA) {
                    areaName = "ERP"
                    addAllAreaEntities(listOf("customer", "invoice"))
                }.asCapability()
            reg.register(golem)
            val parsed = reg.get("golem-erp")?.capability?.agent
            parsed.shouldNotBeNull()
            parsed.areaName shouldBe "ERP"
            parsed.areaEntitiesList shouldContainAll listOf("customer", "invoice")
        }

        "fixture-registered entries carry no last_heartbeat_at" {
            val rid = reg.register(toolCapability("theseus.query:v1").asCapability(), fromFixture = true)
            rid.shouldNotBeBlank()
            reg.get("theseus.query:v1")?.lastHeartbeatAt shouldBe null
        }

        "runtime-registered entries carry last_heartbeat_at == clock now" {
            reg.register(toolCapability("theseus.query:v1").asCapability())
            reg.get("theseus.query:v1")?.lastHeartbeatAt shouldBe Instant.parse("2026-05-28T12:00:00Z")
        }
    })
