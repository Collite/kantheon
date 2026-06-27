package org.tatrman.kantheon.pythia

import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.HitlProfile
import org.tatrman.kantheon.capabilities.v1.IntentKind

/**
 * Registers Pythia into capabilities-mcp at boot as the **`INVESTIGATOR`** agent
 * (Stage 5.1 T5), replacing the seed/fixture manifest with live heartbeat
 * registration. Routable for RCA / FORECAST / SIMULATION (+ PROCEDURAL only for
 * cross-domain). Warn-and-continue via the shared [CapabilitiesClient] (background
 * register + heartbeat with backoff; a missing registry never blocks boot —
 * architecture §7). The [register] seam is injected so the payload + gating are
 * unit-testable without real HTTP.
 *
 * The router few-shot content (`description_for_router`, example/counter questions)
 * is Bora-owned and lives in the authoritative `pythia.yaml`; this code manifest
 * carries the Claude-owned operational fields (endpoint, latency, cost from
 * measurements) so heartbeat registration is self-sufficient.
 */
class PythiaRegistration(
    private val endpoint: String,
    private val serviceEndpoint: String = "http://pythia.kantheon.svc.cluster.local:7090",
    private val heartbeatSeconds: Long = DEFAULT_HEARTBEAT_SECONDS,
    private val log: (String) -> Unit = {},
    private val register: (Capability, String, Long) -> CapabilitiesClientHandle =
        { cap, ep, hb -> CapabilitiesClient.startupRegister(cap, ep, hb) },
) {
    private var handle: CapabilitiesClientHandle? = null

    /** The Pythia AgentCapability (Claude-owned operational fields; router few-shot lives in pythia.yaml). */
    fun manifest(): AgentCapability =
        AgentCapability
            .newBuilder()
            .setAgentKind(AgentKind.INVESTIGATOR)
            .setAgentId("pythia")
            .setDisplayName("Pythia")
            .addAllIntentKindsSupported(
                listOf(IntentKind.RCA, IntentKind.FORECAST, IntentKind.SIMULATION, IntentKind.PROCEDURAL),
            ).setDescriptionForRouter(
                "Pythia — the autonomous analytical investigator. Route here when the question crosses " +
                    "domains, needs hypothesis generation, or is an RCA / forecast / simulation pipeline.",
            ).setServiceEndpoint(serviceEndpoint)
            .setHealthCheckPath("/health")
            .setTypicalLatencyMs(30_000)
            .setTypicalCostUsd(0.15)
            .setHitlDefault(HitlProfile.INTERACTIVE)
            .build()

    /** Kicks off background registration. No-op when the endpoint is blank. */
    fun start() {
        if (endpoint.isBlank()) {
            log("capabilities.url not set — Pythia not registered (fixture manifest stands).")
            return
        }
        val cap = Capability.newBuilder().setAgent(manifest()).build()
        val h = register(cap, endpoint, heartbeatSeconds * MILLIS_PER_SECOND)
        handle = h
        if (h.registrationId != null) {
            log("Pythia registered (INVESTIGATOR) at $endpoint")
        } else {
            log("Pythia startup register at $endpoint pending; background retry continues — boot proceeds.")
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
