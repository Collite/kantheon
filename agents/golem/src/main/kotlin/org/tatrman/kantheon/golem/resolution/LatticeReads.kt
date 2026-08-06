package org.tatrman.kantheon.golem.resolution

import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.TargetClass

// Reads over the lattice that more than one part of the resolution path needs. Read-only by
// construction — nothing here builds or reopens a proto message.

/**
 * `op:` bindings in span order — the order the user said them in.
 *
 * ⛑ **One implementation, deliberately.** This lived twice, byte-identical, in
 * `compose/StructuredQuestion.kt` and `intent/TurnIntent.kt` — and the two are not independent
 * facts: `classifyTurnIntent` uses it to decide whether operator evidence fills an intent
 * vacuum, `composeStructuredQuestion` uses it to decide what the question asks for, and
 * `SelectionStub` uses it to decide what a refusal can still offer. If those three ever
 * disagreed about which bindings count as operators, a turn could be classified DATA_QUERY on
 * evidence the composition then ignored. Placed in the parent package so neither leaf package
 * has to depend on the other.
 *
 * Reads mentions only — an operator is a word the user said, so it is a mention-layer fact; a
 * value cannot be an operator.
 */
fun operatorRefs(lattice: ResolutionState?): List<String> {
    if (lattice == null) return emptyList()
    return lattice.mentionsList
        .sortedBy { it.span.start }
        .flatMap { mention -> mention.bindingsList }
        .filter { it.targetClass == TargetClass.TARGET_CLASS_OPERATOR }
        .map { it.ref }
        .distinct()
}
