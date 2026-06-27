package org.tatrman.kantheon.golem.shem

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.Capability

/**
 * [ShemRegistration] wiring — that it builds the agent [Capability] from the loaded
 * Shem and registers at the resolved endpoint, with the blank-endpoint skip path.
 * The HTTP register/heartbeat machinery itself is covered by capabilities-client.
 */
class ShemRegistrationSpec :
    StringSpec({

        val shem = assembledShemContext() // golem-erp, visibility_roles: [kantheon-area-erp]

        "start() registers the Shem's AgentCapability at the endpoint" {
            var captured: Capability? = null
            var capturedEndpoint: String? = null
            val handle = mockk<CapabilitiesClientHandle>(relaxed = true)
            every { handle.registrationId } returns "rid-1"

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
            captured!!.agent.agentId shouldBe "golem-erp"
            captured!!.agent.visibilityRolesList shouldBe listOf("kantheon-area-erp")

            reg.shutdown()
            verify { handle.shutdown() }
        }

        "a blank endpoint skips registration (no register call, shutdown safe)" {
            var called = false
            val reg =
                ShemRegistration(
                    shem = shem,
                    endpoint = "",
                    register = { _, _, _ ->
                        called = true
                        mockk(relaxed = true)
                    },
                )
            reg.start()
            called shouldBe false
            reg.shutdown() // no handle — must not throw
        }

        "endpointFrom: env wins over config" {
            val config = ConfigFactory.parseString("""golem { capabilities { url = "http://from-config:7501" } }""")
            ShemRegistration.endpointFrom(config, env = "http://from-env:7501") shouldBe "http://from-env:7501"
        }

        "endpointFrom: falls back to config when env unset" {
            val config = ConfigFactory.parseString("""golem { capabilities { url = "http://from-config:7501" } }""")
            ShemRegistration.endpointFrom(config, env = null) shouldBe "http://from-config:7501"
        }

        "endpointFrom: blank when neither env nor config is present" {
            ShemRegistration.endpointFrom(ConfigFactory.empty(), env = null) shouldBe ""
        }
    })
