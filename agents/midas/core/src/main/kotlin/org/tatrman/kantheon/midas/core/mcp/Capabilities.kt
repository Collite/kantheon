package org.tatrman.kantheon.midas.core.mcp

import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesClient

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.core.mcp.Capabilities")

/**
 * Registers Midas-core's five tool capabilities with capabilities-mcp at startup
 * (Stage 1.4 T6). Warn-and-continue is built into the shared `capabilities-client`:
 * an unreachable registry never blocks boot — the background loop retries register
 * with backoff and then heartbeats every 30s. One [CapabilitiesClient.startupRegister]
 * call per capability, so a failure on one tool does not block the others.
 *
 * Endpoint resolution mirrors the thin `-mcp` wrapper services: `CAPABILITIES_MCP_URL`
 * wins, else `midas-core.capabilities.url`. A blank endpoint skips registration.
 */
internal fun registerWithCapabilities(config: Config) {
    val endpoint =
        System.getenv("CAPABILITIES_MCP_URL")
            ?: if (config.hasPath("midas-core.capabilities.url")) {
                config.getString("midas-core.capabilities.url")
            } else {
                ""
            }
    val capabilities = ManifestLoader().loadAll()
    if (capabilities.isEmpty()) {
        log.info("No midas-core tool manifests found on the classpath; nothing to register.")
        return
    }
    if (endpoint.isBlank()) {
        log.info(
            "capabilities-mcp endpoint not configured — {} midas-core capabilities are not registered " +
                "(the registry will not find them until CAPABILITIES_MCP_URL / midas-core.capabilities.url is set).",
            capabilities.size,
        )
        return
    }
    var registered = 0
    for (cap in capabilities) {
        val id = cap.tool.capabilityId
        val handle =
            CapabilitiesClient.startupRegister(
                capability = cap,
                endpoint = endpoint,
                heartbeatIntervalMs = 30_000,
            )
        if (handle.registrationId != null) {
            registered++
            log.info("midas-core registered '{}' with capabilities-mcp at {}", id, endpoint)
        } else {
            log.warn(
                "midas-core startup register for '{}' at {} not yet complete (registry may be " +
                    "unreachable); background retry continues.",
                id,
                endpoint,
            )
        }
    }
    log.info("midas-core: {}/{} capabilities registered with {}", registered, capabilities.size, endpoint)
}
