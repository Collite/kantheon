package org.tatrman.kantheon.hebe.mcp

import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.hebe.config.CapabilitiesEnabled

/**
 * Registers a Hebe instance into capabilities-mcp at boot as a **`non_routable`**
 * agent (Hebe arc P3 S3.4, contracts §2) — present for discovery, never a routing
 * candidate. Mirrors Golem's `ShemRegistration`: warn-and-continue via the shared
 * [CapabilitiesClient] (background register + heartbeat with backoff; a missing
 * registry never blocks boot — registration is presence-only, architecture §3).
 *
 * Gated by the resolved `capabilities.enabled` axis (Stage 2.1), not the profile
 * name: `DISABLED` (`local`) never registers; `OPTIONAL` (`personal`) and `ENABLED`
 * (`server`/`k8s`) register. The [register] seam is injected so the payload + gating
 * are unit-testable without real HTTP.
 */
@Suppress("LongParameterList")
class HebeRegistration(
    private val instanceId: String,
    private val capabilitiesEnabled: CapabilitiesEnabled,
    private val endpoint: String,
    private val serviceEndpoint: String,
    private val heartbeatSeconds: Long = DEFAULT_HEARTBEAT_SECONDS,
    private val log: (String) -> Unit = {},
    private val register: (Capability, String, Long) -> CapabilitiesClientHandle =
        { cap, ep, hb -> CapabilitiesClient.startupRegister(cap, ep, hb) },
) {
    private var handle: CapabilitiesClientHandle? = null

    /** The instance manifest (contracts §2): `non_routable`, no router description/intents. */
    fun manifest(): AgentCapability =
        AgentCapability
            .newBuilder()
            .setAgentKind(AgentKind.PERSONAL_ASSISTANT)
            .setAgentId("hebe-$instanceId")
            .setDisplayName("Hebe ($instanceId)")
            .setNonRoutable(true)
            .setDescriptionForRouter("")
            .setServiceEndpoint(serviceEndpoint)
            .setHealthCheckPath("/healthz")
            .setHitlDefault(HitlProfile.SPECULATIVE)
            .build()

    /** Kicks off background registration. No-op when disabled or the endpoint is blank. */
    fun start() {
        if (capabilitiesEnabled == CapabilitiesEnabled.DISABLED) {
            log("capabilities.enabled=false — Hebe instance '$instanceId' not registered.")
            return
        }
        if (endpoint.isBlank()) {
            log("capabilities.url not set — Hebe instance '$instanceId' not registered.")
            return
        }
        val cap = Capability.newBuilder().setAgent(manifest()).build()
        val h = register(cap, endpoint, heartbeatSeconds * MILLIS_PER_SECOND)
        handle = h
        if (h.registrationId != null) {
            log("Hebe instance 'hebe-$instanceId' registered (non_routable) at $endpoint")
        } else {
            log("Hebe instance 'hebe-$instanceId' startup register at $endpoint pending; background retry continues.")
        }
    }

    /** Stop heartbeats + release the client. */
    fun shutdown() {
        handle?.shutdown()
    }

    companion object {
        const val DEFAULT_HEARTBEAT_SECONDS = 60L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
