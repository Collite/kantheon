package org.tatrman.kantheon.golem.graph

import org.tatrman.kantheon.golem.resolution.TurnEnd
import org.tatrman.kantheon.golem.resolution.ladder.Verdict

/**
 * Walk the RV nodes in the order the shipped strategy's edges put them.
 *
 * ONE copy, used by both `ResolutionGraphSpec` (RV-P5.3 T6) and the conformance runner
 * (RV-P5.4 T2), because the topology is itself a claim: *"the fast path reaches the door
 * without entering selection"* is only meaningful if there is a selection node to not enter,
 * and only checkable if the edges are the ones that ship. Two copies of this walk would be
 * two topologies, and the conformance tier would stop testing the one in production.
 *
 * Mirrors `buildGolemGraph`'s edges exactly:
 * ```
 * assessGaps --(assessed == null)--> the LEGACY chain (returned unchanged)
 * assessGaps --EMIT--> fastPath --FellThrough--> selection
 * assessGaps --ASK---> askGap   --FellThrough--> selection
 * assessGaps --REFUSE-----------------------> selection
 * ```
 */
internal suspend fun walkResolutionNodes(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val assessed = assessGapsNode(state, deps)
    // No lattice, or no resolution deps: the RV nodes are inert and the legacy chain runs.
    val verdict = assessed.assessed?.verdict ?: return assessed
    val after =
        when (verdict) {
            Verdict.EMIT -> fastPathNode(assessed, deps)
            Verdict.ASK -> askGapNode(assessed, deps)
            else -> assessed
        }
    return if (after.turnEnd is TurnEnd.FellThrough || after.turnEnd == null) selectionNode(after, deps) else after
}
