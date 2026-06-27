package org.tatrman.kantheon.capabilities.registry

/**
 * Parses the `:vN` version suffix on capability ids and ranks candidates by version.
 *
 * Convention: ToolCapability ids carry an embedded `:vN` semver (e.g. `model.fit.arima:v1`).
 * An unsuffixed lookup (`model.fit.arima`) resolves to the latest version.
 */
object VersionResolver {
    /**
     * Split `"model.fit.arima:v1"` into `"model.fit.arima"` to `"v1"`.
     * Returns the input as base + null version if no suffix is present.
     */
    fun parse(id: String): Pair<String, String?> {
        val colonAt = id.lastIndexOf(':')
        if (colonAt < 0) return id to null
        val suffix = id.substring(colonAt + 1)
        return if (suffix.startsWith("v") && suffix.length > 1 && suffix.drop(1).all { it.isDigit() || it == '.' }) {
            id.substring(0, colonAt) to suffix
        } else {
            id to null
        }
    }

    /**
     * Pick the candidate with the lexicographically-highest version suffix.
     * Candidates without a `:vN` suffix sort to the bottom.
     */
    fun resolveLatest(candidates: List<RegistryEntry>): RegistryEntry? =
        candidates.maxByOrNull {
            val parsed = parse(it.capability.naturalId())
            parsed.second?.let { v -> versionKey(v) } ?: "0"
        }

    fun base(id: String): String = parse(id).first

    /**
     * Sortable numeric key for version comparison: `"v2"` → `"002"`, `"v10"` → `"010"`,
     * `"v1.5"` → `"001.005"`. Avoids lexicographic surprises (`"v10" < "v2"`).
     */
    private fun versionKey(version: String): String =
        version
            .removePrefix("v")
            .split(".")
            .joinToString(".") { it.padStart(3, '0') }
}

internal fun org.tatrman.kantheon.capabilities.v1.Capability.naturalId(): String =
    when {
        hasTool() -> tool.capabilityId
        hasAgent() -> agent.agentId
        else -> error("Capability oneof must be set")
    }
