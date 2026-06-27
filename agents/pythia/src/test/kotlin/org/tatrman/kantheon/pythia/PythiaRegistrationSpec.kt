package org.tatrman.kantheon.pythia

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.IntentKind

/**
 * Stage 5.1 T5 — heartbeat registration replaces the fixture manifest. On boot Pythia
 * posts a routable INVESTIGATOR manifest; a registry-unreachable boot degrades to a
 * warn (the injected register seam returns a handle with no registrationId) and never
 * throws.
 */
class PythiaRegistrationSpec :
    StringSpec({

        fun handle(registered: Boolean): CapabilitiesClientHandle =
            mockk<CapabilitiesClientHandle>(relaxed = true).also {
                every { it.registrationId } returns if (registered) "rid-1" else null
            }

        "the manifest is a routable INVESTIGATOR supporting RCA / FORECAST / SIMULATION" {
            val manifest = PythiaRegistration("http://caps").manifest()
            manifest.agentKind shouldBe AgentKind.INVESTIGATOR
            manifest.agentId shouldBe "pythia"
            manifest.nonRoutable shouldBe false
            manifest.intentKindsSupportedList shouldContainAll
                listOf(IntentKind.RCA, IntentKind.FORECAST, IntentKind.SIMULATION)
        }

        "on boot the manifest is posted to capabilities-mcp" {
            var posted: Capability? = null
            val reg =
                PythiaRegistration(
                    endpoint = "http://caps",
                    register = { cap, _, _ ->
                        posted = cap
                        handle(registered = true)
                    },
                )
            reg.start()
            posted!!.agent.agentId shouldBe "pythia"
        }

        "a registry-unreachable boot degrades to a warn (no registrationId) and never throws" {
            val logs = mutableListOf<String>()
            val reg =
                PythiaRegistration(
                    endpoint = "http://caps",
                    log = { logs += it },
                    register = { _, _, _ -> handle(registered = false) }, // simulates unreachable → backoff
                )
            reg.start() // must not throw
            logs.any { it.contains("pending") } shouldBe true
        }

        "a blank endpoint is a no-op (the fixture manifest stands)" {
            var called = false
            val reg =
                PythiaRegistration(
                    endpoint = "",
                    register = { _, _, _ ->
                        called = true
                        handle(registered = true)
                    },
                )
            reg.start()
            called shouldBe false
        }
    })
