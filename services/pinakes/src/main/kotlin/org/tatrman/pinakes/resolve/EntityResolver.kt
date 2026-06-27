package org.tatrman.pinakes.resolve

import org.tatrman.pinakes.compile.PageDraft

/**
 * A view of the entities already resolved in the corpus — the GLOBAL graph the
 * resolver reconciles against (architecture §7: "resolve is GLOBAL and conformed
 * … so Kaufland is one node regardless of feed"). Kallimachos-backed in the
 * running service; a fake accumulator in the spec.
 */
interface ConceptIndex {
    fun findEntity(
        entityType: String,
        displayLabel: String,
    ): ExistingEntity?

    /** Register a newly-resolved entity so later feeds in the same corpus merge into it. */
    fun register(
        entityType: String,
        displayLabel: String,
        entityId: String,
        pageId: Long?,
    )
}

data class ExistingEntity(
    val entityId: String,
    val displayLabel: String,
    val pageId: Long?,
)

enum class ResolveOutcome { NEW, MERGED }

data class ResolvedPage(
    val draft: PageDraft,
    val outcome: ResolveOutcome,
    val mergedIntoPageId: Long? = null,
)

/**
 * The global `RESOLVE` stage (architecture §7 — conformed tail, NEVER
 * per-pipeline, or the wiki fragments, risks §14). Reconciles each ENTITY/CONCEPT
 * draft against the whole corpus: an existing match MERGES (reuse its entity id /
 * page), otherwise it is NEW. Non-entity pages (SUMMARY/OVERVIEW) are always new.
 *
 * `concept_ref` is populated WIKI-LOCAL (`entity_type`+`entity_id`+label;
 * `ariadne_qname` empty) — the §6/§12 Ariadne seam, reserved, not yet bridged.
 * Outcomes feed `pinakes_entities_resolved_total{outcome}` (emitted by the stage).
 */
class EntityResolver(
    private val index: ConceptIndex,
) {
    fun resolve(drafts: List<PageDraft>): List<ResolvedPage> =
        drafts.map { draft ->
            val ref = draft.conceptRef ?: return@map ResolvedPage(draft, ResolveOutcome.NEW)
            val existing = index.findEntity(ref.entityType, ref.displayLabel)
            if (existing != null) {
                // Merge: reuse the conformed entity id across feeds.
                ResolvedPage(
                    draft.copy(conceptRef = ref.copy(entityId = existing.entityId)),
                    ResolveOutcome.MERGED,
                    existing.pageId,
                )
            } else {
                index.register(ref.entityType, ref.displayLabel, ref.entityId, null)
                ResolvedPage(draft, ResolveOutcome.NEW)
            }
        }
}
