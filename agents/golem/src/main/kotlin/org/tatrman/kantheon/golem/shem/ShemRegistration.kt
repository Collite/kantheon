package org.tatrman.kantheon.golem.shem

import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import org.tatrman.kantheon.capabilities.client.CapabilitiesClientHandle
import org.tatrman.kantheon.capabilities.v1.Capability

private val log = LoggerFactory.getLogger(ShemRegistration::class.java)

/**
 * Registers the pod's loaded Shem (an `AgentCapability`, incl. `visibility_roles`)
 * into capabilities-mcp at boot via the shared [CapabilitiesClient] — the
 * query-mcp warn-and-continue pattern: registration runs in the background with
 * backoff, so a missing/unreachable registry never blocks Golem from serving.
 *
 * Endpoint comes from `CAPABILITIES_MCP_URL` or `golem.capabilities.url`; blank →
 * the Shem isn't registered (local boot / tests) and `start()` is a no-op. The
 * [register] seam is injected so the wiring is unit-testable without real HTTP.
 */
class ShemRegistration(
    private val shem: ShemContext,
    private val endpoint: String,
    private val heartbeatIntervalMs: Long = 30_000,
    private val register: (Capability, String, Long) -> CapabilitiesClientHandle =
        { cap, ep, hb -> CapabilitiesClient.startupRegister(cap, ep, hb) },
) {
    private var handle: CapabilitiesClientHandle? = null

    /** Build the agent [Capability] and kick off background registration. No-op when [endpoint] is blank. */
    fun start() {
        if (endpoint.isBlank()) {
            log.info("CAPABILITIES_MCP_URL/golem.capabilities.url not set — Shem '{}' not registered.", shem.golemId)
            return
        }
        val cap = Capability.newBuilder().setAgent(shem.manifest).build()
        val h = register(cap, endpoint, heartbeatIntervalMs)
        handle = h
        if (h.registrationId != null) {
            log.info("Shem '{}' registered with capabilities-mcp at {}", shem.golemId, endpoint)
        } else {
            log.warn(
                "Shem '{}' startup register at {} not yet complete; background retry continues.",
                shem.golemId,
                endpoint,
            )
        }
    }

    /** Stop heartbeats + release the client (called from ApplicationStopping). */
    fun shutdown() {
        handle?.shutdown()
    }

    companion object {
        /** Resolve the capabilities-mcp endpoint: env wins, then config, else blank (= skip). */
        fun endpointFrom(
            config: Config,
            env: String? = System.getenv("CAPABILITIES_MCP_URL"),
        ): String =
            env
                ?: if (config.hasPath("golem.capabilities.url")) config.getString("golem.capabilities.url") else ""
    }
}
