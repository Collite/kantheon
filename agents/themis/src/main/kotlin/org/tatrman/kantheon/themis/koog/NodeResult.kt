package org.tatrman.kantheon.themis.koog

import org.tatrman.kantheon.themis.v1.Themis

/**
 * Terminal outcome of a Themis resolution. The per-node step functions under
 * `koog/nodes/` encode the outcome in `ParseState.terminal*`; [runThemisGraph]
 * packs it into one of these via [ResolverContext.toNodeResult] after the Koog
 * graph runs, and the HTTP/MCP resolve handlers project it onto the wire.
 */
sealed class NodeResult {
    data class Continue(
        val state: ResolverContext,
    ) : NodeResult()

    data class EmitResolution(
        val resolution: Themis.Resolution,
        val state: ResolverContext,
    ) : NodeResult()

    data class EmitAwaiting(
        val awaiting: Themis.AwaitingClarification,
        val state: ResolverContext,
    ) : NodeResult()

    data class EmitRefusal(
        val refusal: Themis.RefusalWithGaps,
        val state: ResolverContext,
    ) : NodeResult()

    data class Error(
        val message: String,
        val state: ResolverContext,
    ) : NodeResult()
}

/**
 * Packs the terminal carriers on [ResolverContext.parseState] into a
 * [NodeResult]. Used after the Koog graph runs `AIAgent.run` to expose the
 * same outer-API contract as the legacy dispatch loop.
 */
fun ResolverContext.toNodeResult(): NodeResult {
    val ps = parseState
    return when {
        ps.terminalRefusal != null -> NodeResult.EmitRefusal(ps.terminalRefusal, this)
        ps.terminalResolution != null -> NodeResult.EmitResolution(ps.terminalResolution, this)
        ps.terminalAwaiting != null -> NodeResult.EmitAwaiting(ps.terminalAwaiting, this)
        ps.terminalError != null -> NodeResult.Error(ps.terminalError, this)
        else -> NodeResult.Continue(this)
    }
}
