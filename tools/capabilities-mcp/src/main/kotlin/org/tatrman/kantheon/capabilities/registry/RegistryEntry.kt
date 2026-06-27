package org.tatrman.kantheon.capabilities.registry

import org.tatrman.kantheon.capabilities.v1.Capability
import java.time.Instant

/**
 * One entry in the [InMemoryRegistry].
 *
 * `lastHeartbeatAt = null` flags a source-controlled fixture (loaded from a YAML
 * manifest at boot). Such entries are exempt from TTL pruning.
 *
 * `pruned = true` means the TTL pruner has decided the entry is stale; it is
 * hidden from `list*()` queries by default but still retrievable via `get(id)`
 * for audit semantics (per contracts.md §1.1 notes).
 */
data class RegistryEntry(
    val capability: Capability,
    val registrationId: String,
    val lastHeartbeatAt: Instant?,
    val registeredAt: Instant,
    val pruned: Boolean = false,
)
