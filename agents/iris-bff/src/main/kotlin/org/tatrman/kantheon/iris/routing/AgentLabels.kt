package org.tatrman.kantheon.iris.routing

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.iris.routing.AgentLabels")

/**
 * Resolves an `agent_id` to a human display name for RoutingPickChip labels
 * (Stage 3.1 T5). Best-effort: any resolution failure falls back to the raw id,
 * so chips always render. The richer capabilities-driven chip metadata (dynamic
 * chips, alternates) lands in Stage 3.2.
 */
fun interface AgentLabels {
    suspend fun displayName(agentId: String): String

    companion object {
        /** Identity resolver (label == id). The unit/component-test default. */
        val IDENTITY = AgentLabels { it }
    }
}

/**
 * Capabilities-mcp-backed [AgentLabels]. Reads the agent manifest and extracts a
 * display name from the common keys; on any miss/error returns the id verbatim
 * (warn-and-continue — a label lookup must never fail a routing turn).
 */
class CapabilitiesAgentLabels(
    private val client: CapabilitiesReadClient,
) : AgentLabels {
    override suspend fun displayName(agentId: String): String =
        try {
            val manifest = client.get(agentId)
            DISPLAY_KEYS.firstNotNullOfOrNull { key ->
                runCatching { manifest[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching {
                        manifest["manifest"]
                            ?.jsonObject
                            ?.get(key)
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
            } ?: agentId
        } catch (e: Throwable) {
            log.debug("capabilities label lookup failed for '{}' — falling back to id", agentId, e)
            agentId
        }

    private companion object {
        val DISPLAY_KEYS = listOf("displayName", "display_name", "name")
    }
}
