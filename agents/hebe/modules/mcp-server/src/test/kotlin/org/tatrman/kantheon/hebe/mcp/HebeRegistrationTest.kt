package org.tatrman.kantheon.hebe.mcp

import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.hebe.config.CapabilitiesEnabled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hebe registration payload + gating (Hebe P3 S3.4 T1/T2). The manifest matches
 * contracts §2 (`non_routable`, no router description/intents); registration is
 * gated by the `capabilities.enabled` axis, warn-and-continue.
 */
class HebeRegistrationTest {
    private fun handle() =
        mockk<CapabilitiesClientHandle>(relaxed = true).also {
            every { it.registrationId } returns "rid-1"
        }

    private fun registration(
        enabled: CapabilitiesEnabled,
        endpoint: String = "http://capabilities-mcp:8080",
        captured: MutableList<Capability> = mutableListOf(),
    ) = HebeRegistration(
        instanceId = "bora",
        capabilitiesEnabled = enabled,
        endpoint = endpoint,
        serviceEndpoint = "http://hebe-bora.kantheon.svc:8765",
        register = { cap, _, _ ->
            captured.add(cap)
            handle()
        },
    )

    @Test
    fun `manifest matches the contracts manifest - non_routable, no router fields`() {
        val m = registration(CapabilitiesEnabled.ENABLED).manifest()
        assertEquals("hebe-bora", m.agentId)
        assertEquals(AgentKind.PERSONAL_ASSISTANT, m.agentKind)
        assertTrue(m.nonRoutable, "Hebe must register non_routable")
        assertEquals("", m.descriptionForRouter)
        assertTrue(m.intentKindsSupportedList.isEmpty())
        assertEquals("/healthz", m.healthCheckPath)
        assertEquals(HitlProfile.SPECULATIVE, m.hitlDefault)
        assertEquals("http://hebe-bora.kantheon.svc:8765", m.serviceEndpoint)
    }

    @Test
    fun `disabled capabilities axis does not register`() {
        val captured = mutableListOf<Capability>()
        registration(CapabilitiesEnabled.DISABLED, captured = captured).start()
        assertTrue(captured.isEmpty(), "local (capabilities.enabled=false) must not register")
    }

    @Test
    fun `optional capabilities axis registers the non_routable agent`() {
        val captured = mutableListOf<Capability>()
        registration(CapabilitiesEnabled.OPTIONAL, captured = captured).start()
        assertEquals(1, captured.size)
        assertEquals("hebe-bora", captured.single().agent.agentId)
        assertTrue(captured.single().agent.nonRoutable)
    }

    @Test
    fun `blank endpoint does not register even when enabled`() {
        val captured = mutableListOf<Capability>()
        registration(CapabilitiesEnabled.ENABLED, endpoint = "", captured = captured).start()
        assertTrue(captured.isEmpty())
    }
}
