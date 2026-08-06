package org.tatrman.kantheon.golem.graph

/**
 * Walk the RV nodes in the order the shipped strategy's edges put them.
 *
 * ONE copy, used by both `ResolutionGraphSpec` (RV-P5.3 T6) and the conformance runner
 * (RV-P5.4 T2), because the topology is itself a claim: *"the fast path reaches the door
 * without entering selection"* is only meaningful if there is a selection node to not enter,
 * and only checkable if the edges are the ones that ship. Two copies of this walk would be
 * two topologies, and the conformance tier would stop testing the one in production.
 *
 * ⛑ **The conditions are not re-stated here — they are [routeAfterAssess] and [routeAfterExit],
 * the same functions `buildGolemGraph`'s edges call.** They used to be re-stated, and the two
 * had already drifted: this walk sent a CLIMB verdict and a null `turnEnd` to `selection` where
 * the shipped graph had no edge for the first and finished on the second. A mirror maintained
 * by hand is a second topology wearing the first one's name.
 *
 * ```
 * assessGaps --LEGACY----> the legacy chain (see below)
 * assessGaps --FAST_PATH-> fastPath  --SELECTION--> selection
 * assessGaps --ASK-------> askGap    --SELECTION--> selection
 * assessGaps --SELECTION-> selection
 * ```
 *
 * The one deliberate difference: on [RvRoute.LEGACY] the shipped graph forwards to
 * `resolveSelection` and runs `composePlan → gatePlan → …`; this walk returns the state
 * unchanged, because the harness wires the legacy deps as relaxed mocks and running them would
 * assert nothing. `ConversationRunner` treats any legacy-node invocation as its own finding.
 */
internal suspend fun walkResolutionNodes(
    state: GolemTurnState,
    deps: GolemGraphDeps,
): GolemTurnState {
    val assessed = assessGapsNode(state, deps)
    val after =
        when (routeAfterAssess(assessed)) {
            // No lattice, or no resolution deps: the RV nodes are inert and the legacy chain
            // runs. Nothing further to walk — see the KDoc.
            RvRoute.LEGACY -> return assessed
            RvRoute.FAST_PATH -> fastPathNode(assessed, deps)
            RvRoute.ASK -> askGapNode(assessed, deps)
            RvRoute.SELECTION -> assessed
            RvRoute.FINISH -> assessed
        }
    return when (routeAfterExit(after)) {
        RvRoute.SELECTION -> selectionNode(after, deps)
        else -> after
    }
}
