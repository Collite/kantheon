package org.tatrman.kantheon.golem.shem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import java.nio.file.Files
import java.nio.file.Path

/**
 * Midas Stage 3.1 T5 — the golem-investment Shem registers **one** `AgentCapability`
 * (`agent_kind = AREA_QA`, `agent_id = golem-investment`) carrying its entitlement role
 * and the midas tool refs. Built from the real overlay via the `identity` placeholder
 * (the pre-model-load capability registration holds). Live registration/heartbeat is
 * warn-and-continue (covered by capabilities-client); here the `register` seam is injected.
 */
class GolemInvestmentRegistrationSpec :
    StringSpec({

        "the golem-investment Shem registers one AREA_QA AgentCapability with its role + tool refs" {
            val overlay =
                ShemOverlayParser.parse(Files.readString(Path.of("shems/golem-investment/shem.yaml")))
            val shem = ShemContext(ShemAssembler.identity(overlay))

            var captured: Capability? = null
            var capturedEndpoint: String? = null
            val handle = mockk<CapabilitiesClientHandle>(relaxed = true)
            every { handle.registrationId } returns "rid-inv"

            val reg =
                ShemRegistration(
                    shem = shem,
                    endpoint = "http://capabilities-mcp:7501",
                    register = { cap, ep, _ ->
                        captured = cap
                        capturedEndpoint = ep
                        handle
                    },
                )
            reg.start()

            capturedEndpoint shouldBe "http://capabilities-mcp:7501"
            captured!!.hasAgent() shouldBe true
            captured!!.agent.agentKind shouldBe AgentKind.AREA_QA
            captured!!.agent.agentId shouldBe "golem-investment"
            captured!!.agent.visibilityRolesList shouldContain "kantheon-area-investment"
            captured!!.agent.capabilityRefsList shouldContainAll
                listOf(
                    "midas.portfolio.performance:v1",
                    "midas.position.cost_basis:v1",
                    "midas.reconcile.statement:v1",
                )

            reg.shutdown()
        }
    })
