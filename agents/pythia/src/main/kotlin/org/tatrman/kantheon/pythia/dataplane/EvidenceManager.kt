package org.tatrman.kantheon.pythia.dataplane

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.Handle

/** What finalisation did with the investigation's handles. */
data class FinaliseResult(
    val persisted: List<String> = emptyList(),
    val evicted: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Evidence persistence + GC at finalisation (Stage 4.1 T4, design §6.2). On
 * completion: **load-bearing** handles (referenced by a supported hypothesis / the
 * conclusion) are persisted to the `pythia-evidence` bucket with the depth-derived
 * retention tag (production 90 d / shallow 7 d); **transient** handles backed by a
 * worker session / Redis / a non-evidence blob are evicted (`Charon.Evict`).
 * Pythia-internal handles (LiveQueryRef / PgResultSnapshot) live in Pythia's PG and
 * are GC'd locally — never sent to Charon.
 */
class EvidenceManager(
    private val charon: CharonClient,
    private val policy: MaterialisationPolicy,
    private val materialiser: Materialiser,
) {
    private val log = LoggerFactory.getLogger(EvidenceManager::class.java)

    suspend fun finalise(
        investigationId: String,
        handles: HandleTable,
        loadBearingHandleIds: Set<String>,
        depth: DepthBudget,
        allHandleIds: Collection<String>,
    ): FinaliseResult {
        val persisted = mutableListOf<String>()
        val evicted = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for (handleId in allHandleIds) {
            val handle = handles.get(handleId) ?: continue
            if (handleId in loadBearingHandleIds) {
                val move = policy.persistEvidence(handle, investigationId, depth, loadBearing = true)
                if (move != null) {
                    runCatching { materialiser.apply(move, handles) }
                        .onSuccess { persisted += handleId }
                        .onFailure { warnings += "evidence persist failed for $handleId: ${it.message}" }
                } else {
                    // Already durable (a Seaweed blob) — count as retained, no move needed.
                    if (handle.kindCase == Handle.KindCase.SEAWEED) persisted += handleId
                }
            } else if (isEvictable(handle)) {
                runCatching { charon.evict(HandleLocationMapping.toLocation(handle)) }
                    .onSuccess { evicted += handleId }
                    .onFailure { log.warn("evict failed for {}: {}", handleId, it.message) }
            }
        }
        return FinaliseResult(persisted, evicted, warnings)
    }

    /** Transient Charon-backed kinds are evictable; internal kinds and DB tables are not. */
    private fun isEvictable(handle: Handle): Boolean =
        when (handle.kindCase) {
            Handle.KindCase.WORKER_DF, Handle.KindCase.REDIS, Handle.KindCase.SEAWEED -> true
            else -> false // LiveQueryRef / PgResultSnapshot (local) / DbTable (owner's job)
        }
}
