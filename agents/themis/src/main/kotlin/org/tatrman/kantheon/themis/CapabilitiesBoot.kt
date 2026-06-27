package org.tatrman.kantheon.themis

import kotlinx.serialization.json.jsonArray
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.capabilities.client.CapabilitiesUnreachableException

/**
 * Phase 3 Stage 3.3 — fail-fast boot gate. Themis refuses to start unless
 * capabilities-mcp is reachable AND returns at least one agent; otherwise
 * `routeToAgent` would silently funnel every question to Layer 3 (user-pick).
 *
 * - capabilities-mcp unreachable → [IllegalStateException].
 * - reachable but empty agent list → [IllegalArgumentException] (via `require`).
 */
suspend fun assertRoutableAgentsAvailable(capabilities: CapabilitiesReadClient) {
    val agents =
        try {
            capabilities.listAgents()["agents"]?.jsonArray
        } catch (e: CapabilitiesUnreachableException) {
            throw IllegalStateException(
                "capabilities-mcp unreachable at startup — refusing to start. ${e.message}",
                e,
            )
        }
    require(agents != null && agents.isNotEmpty()) {
        "capabilities-mcp returned no agents at startup — refusing to start to avoid silently " +
            "routing every question to Layer 3. Check the capabilities-mcp deployment and YAML fixtures."
    }
}
