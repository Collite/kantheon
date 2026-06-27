package org.tatrman.pinakes.resolve

/**
 * In-memory global concept index — the conformed resolver's view of the corpus
 * entities (architecture §7). Accumulates resolved entities within the process so
 * "Kaufland" is one node across feeds in a run session. The Kallimachos-backed
 * index (query existing entity pages by `concept_ref`) is the deploy path for
 * cross-restart / cross-instance global resolution (integration-deferred).
 */
class InMemoryConceptIndex : ConceptIndex {
    private val byKey = linkedMapOf<String, ExistingEntity>()

    private fun key(
        entityType: String,
        displayLabel: String,
    ) = "${entityType.lowercase()}|${LabelNormalizer.normalize(displayLabel)}"

    override fun findEntity(
        entityType: String,
        displayLabel: String,
    ): ExistingEntity? = byKey[key(entityType, displayLabel)]

    override fun register(
        entityType: String,
        displayLabel: String,
        entityId: String,
        pageId: Long?,
    ) {
        byKey[key(entityType, displayLabel)] = ExistingEntity(entityId, displayLabel, pageId)
    }
}
