package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import org.tatrman.kantheon.themis.koog.OutcomeState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.token.HmacTokenManager

private val logger = KotlinLogging.logger { }

/**
 * Stage 2.3 T5 — body of `decodeTokenAndApplyChoice`.
 *
 * Verifies the HMAC resume token attached to the request, transplants the
 * encoded `roundCounter` into [ResolverContext.parseState], clears the
 * resumeToken, and returns Continue so the dispatch loop re-runs the rest
 * of the graph with the resumed state.
 *
 * Returns [NodeResult] (not bare [ResolverContext]) because token-decode
 * is inherently terminal-producing on failure: `Error("No resume token")`
 * or `Error("Invalid resume token")`.
 */
fun decodeResumeTokenStep(
    state: ResolverContext,
    tokenManager: HmacTokenManager,
): ResolverContext {
    logger.info { "node=decodeTokenAndApplyChoice conversationId=${state.conversationId}" }

    val token = state.parseState.resumeToken
    if (token == null) {
        return state.copy(
            parseState =
                state.parseState.copy(
                    outcome = OutcomeState.Error,
                    terminalError = "No resume token",
                    lastNode = "decodeTokenAndApplyChoice",
                ),
        )
    }
    val payload = tokenManager.verifyAndDecode(token)
    if (payload == null) {
        return state.copy(
            parseState =
                state.parseState.copy(
                    outcome = OutcomeState.Error,
                    terminalError = "Invalid resume token",
                    lastNode = "decodeTokenAndApplyChoice",
                ),
        )
    }

    logger.info { "Resume token verified for round ${payload.roundCounter}" }

    return state.copy(
        parseState =
            state.parseState.copy(
                roundCounter = payload.roundCounter,
                lastNode = "decodeTokenAndApplyChoice",
                resumeToken = null,
            ),
    )
}
