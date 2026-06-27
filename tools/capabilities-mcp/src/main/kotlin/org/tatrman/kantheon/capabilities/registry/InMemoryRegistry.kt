package org.tatrman.kantheon.capabilities.registry

import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.Capability
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of capabilities (tools + agents). Phase 1 backing store.
 *
 * Concurrency: a single `ConcurrentHashMap` keyed by the capability's natural
 * id (`capability_id` for tools, `agent_id` for agents). `register` is
 * idempotent on that key — same id → same `registration_id`.
 *
 * Lookup semantics:
 *  - `get(id)` honours version-suffix conventions via [VersionResolver]: an
 *    id with no `:vN` suffix resolves to the latest version, an id with a
 *    suffix returns exactly that version.
 *  - Pruned entries are returned by `get(id)` (audit semantics) but excluded
 *    from `list()` / `listAgents()`.
 */
class InMemoryRegistry(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val byId = ConcurrentHashMap<String, RegistryEntry>()

    /**
     * Register or update a capability. Returns the (stable across re-registers)
     * `registration_id`.
     *
     * `fromFixture = true` means the entry came from a source-controlled YAML
     * manifest — its `lastHeartbeatAt` stays null and the TTL pruner ignores it.
     */
    fun register(
        capability: Capability,
        fromFixture: Boolean = false,
    ): String {
        val id = capability.naturalId()
        val now = Instant.now(clock)
        return byId
            .compute(id) { _, existing ->
                val registrationId = existing?.registrationId ?: UUID.randomUUID().toString()
                RegistryEntry(
                    capability = capability,
                    registrationId = registrationId,
                    lastHeartbeatAt =
                        when {
                            fromFixture -> null
                            else -> now
                        },
                    registeredAt = existing?.registeredAt ?: now,
                    // a fresh register/heartbeat un-prunes
                    pruned = false,
                )
            }!!
            .registrationId
    }

    fun get(id: String): RegistryEntry? {
        // Exact match (handles `model.fit.arima:v1` lookups verbatim) first.
        byId[id]?.let { return it }
        // Then try as version-stripped base id: pick the highest version.
        val (base, requestedVersion) = VersionResolver.parse(id)
        if (requestedVersion != null) {
            // Caller asked for a specific version that doesn't exist — null.
            return null
        }
        val matches = byId.values.filter { VersionResolver.base(it.capability.naturalId()) == base }
        return VersionResolver.resolveLatest(matches)
    }

    fun list(): List<RegistryEntry> = byId.values.filterNot { it.pruned }

    fun listIncludingPruned(): List<RegistryEntry> = byId.values.toList()

    fun listAgents(): List<AgentCapability> =
        list()
            .filter { it.capability.hasAgent() }
            .map { it.capability.agent }

    /**
     * Mark entries whose `lastHeartbeatAt` is older than [cutoff] as pruned.
     * Fixtures (`lastHeartbeatAt == null`) are exempt. Returns the count of
     * entries newly pruned.
     */
    fun markPrunedOlderThan(cutoff: Instant): Int {
        var pruned = 0
        byId.replaceAll { _, entry ->
            val staleHeartbeat = entry.lastHeartbeatAt?.isBefore(cutoff) == true
            if (staleHeartbeat && !entry.pruned) {
                pruned++
                entry.copy(pruned = true)
            } else {
                entry
            }
        }
        return pruned
    }
}
