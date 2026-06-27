package org.tatrman.kantheon.capabilities.registry

import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.CapabilityFilter
import org.tatrman.kantheon.capabilities.v1.IntentKind
import java.time.Clock
import java.time.Instant

/**
 * Domain layer over [InMemoryRegistry] shared by MCP and REST surfaces.
 *
 * Owns query semantics — filter handling, capability-tag matching, intent-kind
 * narrowing — so neither the MCP nor the REST handler has to re-implement them.
 *
 * Mutation (`register` / `heartbeat`) returns a sealed [Outcome] so handlers can
 * surface a `messages` entry on the failure cases without throwing.
 */
class RegistryQueryService(
    private val registry: InMemoryRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class SearchParams(
        val intentKinds: List<IntentKind> = emptyList(),
        val entityTypes: List<String> = emptyList(),
        val capabilityTags: List<String> = emptyList(),
        val filter: CapabilityFilter? = null,
    )

    data class ListParams(
        val category: String? = null,
        val filter: CapabilityFilter? = null,
    )

    sealed interface HeartbeatOutcome {
        data class Accepted(
            val acceptedAt: Instant,
        ) : HeartbeatOutcome

        data object Unknown : HeartbeatOutcome
    }

    fun search(params: SearchParams): List<Capability> =
        registry
            .listIncludingPruned()
            .applyFilter(params.filter)
            .map { it.capability }
            .filter { it.matchesIntents(params.intentKinds) }
            .filter { it.matchesEntityTypes(params.entityTypes) }
            .filter { it.matchesTags(params.capabilityTags) }

    fun list(params: ListParams): List<Capability> =
        registry
            .listIncludingPruned()
            .applyFilter(params.filter)
            .map { it.capability }
            .filter { params.category == null || (it.hasTool() && it.tool.category == params.category) }

    /**
     * The **routing view** served to Themis. Excludes `non_routable` agents (Hebe;
     * P3 S3.4 T4) — they are never routing candidates across any Themis layer. Plain
     * `list`/`get`/`search` keep them for discovery; only this routing view drops them.
     */
    fun listAgents(filter: CapabilityFilter? = null): List<AgentCapability> =
        registry
            .listIncludingPruned()
            .applyFilter(filter ?: agentsDefaultFilter())
            .map { it.capability }
            .filter { it.hasAgent() }
            .map { it.agent }
            .filter { !it.nonRoutable }

    fun get(id: String): Capability? = registry.get(id)?.capability

    fun register(capability: Capability): String = registry.register(capability)

    fun heartbeat(registrationId: String): HeartbeatOutcome {
        val entry =
            registry.listIncludingPruned().firstOrNull { it.registrationId == registrationId }
                ?: return HeartbeatOutcome.Unknown
        val acceptedAt = Instant.now(clock)
        // Refresh by re-registering with the same capability (idempotent on natural id).
        registry.register(entry.capability)
        return HeartbeatOutcome.Accepted(acceptedAt)
    }

    private fun List<RegistryEntry>.applyFilter(filter: CapabilityFilter?): List<RegistryEntry> {
        val includeTools = filter?.takeIf { it.hasIncludeTools() }?.includeTools ?: true
        val includeAgents = filter?.takeIf { it.hasIncludeAgents() }?.includeAgents ?: true
        val includePruned = filter?.takeIf { it.hasIncludePruned() }?.includePruned ?: false
        return filter {
            when {
                it.capability.hasTool() && !includeTools -> false
                it.capability.hasAgent() && !includeAgents -> false
                !includePruned && it.pruned -> false
                else -> true
            }
        }
    }

    private fun agentsDefaultFilter(): CapabilityFilter =
        CapabilityFilter
            .newBuilder()
            .setIncludeTools(false)
            .setIncludeAgents(true)
            .build()

    private fun Capability.matchesIntents(want: List<IntentKind>): Boolean {
        if (want.isEmpty()) return true
        if (!hasAgent()) return false
        return agent.intentKindsSupportedList.any { want.contains(it) }
    }

    private fun Capability.matchesEntityTypes(want: List<String>): Boolean {
        if (want.isEmpty()) return true
        if (!hasAgent()) return false
        return agent.areaEntitiesList.any { want.contains(it) }
    }

    private fun Capability.matchesTags(wantTags: List<String>): Boolean {
        if (wantTags.isEmpty()) return true
        if (!hasTool()) return false
        val haystack = tool.searchTagsList + tool.category
        return wantTags.any { wanted -> haystack.any { hay -> hay == wanted || matchesGlob(hay, wanted) } }
    }

    private fun matchesGlob(
        value: String,
        pattern: String,
    ): Boolean {
        val regex =
            pattern
                .replace(".", "\\.")
                .replace("*", ".*")
        return Regex("^$regex$").matches(value)
    }
}
