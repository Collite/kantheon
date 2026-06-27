package org.tatrman.kantheon.pythia.plan

import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient

/**
 * Capability existence against capabilities-mcp: `get(id)` succeeds → exists; a
 * 404 / unreachable raises `CapabilitiesUnreachableException`, treated as "absent"
 * (the planner re-plans; a clean SQL-only plan that names a real query passes).
 */
class RegistryCapabilityChecker(
    private val client: CapabilitiesReadClient,
) : CapabilityChecker {
    override suspend fun exists(capabilityId: String): Boolean = runCatching { client.get(capabilityId) }.isSuccess
}
