package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.koog.InferenceResult
import org.tatrman.kantheon.themis.koog.OutcomeState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }

/**
 * Stage 2.3 T5 — body of `decideHitlOrEmit`.
 *
 * Reads `state.parseState.inferenceResult` and the HITL thresholds from
 * [ResolverAppConfig.hitl]; emits either:
 *  - [NodeResult.EmitResolution] when `confidence >= threshold` OR
 *    `roundCounter >= maxRounds` (forced resolution)
 *  - [NodeResult.EmitAwaiting] otherwise, with a resume token minted from
 *    the current NLP response + ambiguity question.
 *
 * The HMAC resume-token codec ([buildResumeTokenForAwaiting]) preserves the
 * Resolver's verbatim field set so any token issued before the migration
 * remains decodable post-migration. Same goes for [buildAwaitingClarification]
 * — option text and `opt_other` semantics carry verbatim.
 */

fun buildAwaitingClarification(
    state: ResolverContext,
    result: InferenceResult,
): Themis.AwaitingClarification {
    val alternatives =
        result.alternatives.ifEmpty {
            listOf(
                Themis.InferenceAlternative
                    .newBuilder()
                    .setFunctionId(result.functionId)
                    .setArgsJson(result.argsJson)
                    .setConfidence(result.confidence)
                    .setRationale(result.rationale)
                    .build(),
            )
        }

    val options =
        alternatives
            .take(3)
            .mapIndexed { idx, alt ->
                Themis.ClarificationOption
                    .newBuilder()
                    .setOptionId("opt_${idx + 1}")
                    .setLabel("${alt.functionId} (${(alt.confidence * 100).toInt()}%)")
                    .setDescription(alt.rationale)
                    .build()
            }.toMutableList()
    options.add(
        Themis.ClarificationOption
            .newBuilder()
            .setOptionId("opt_other")
            .setLabel("Something else")
            .setDescription("")
            .build(),
    )

    val firstSpan = state.parseState.filteredSpans.firstOrNull()
    val contextSpan =
        if (firstSpan != null) {
            Themis.ClarificationContextSpan
                .newBuilder()
                .setCharStart(firstSpan.charStart)
                .setCharEnd(firstSpan.charEnd)
                .setCoveredText(firstSpan.coveredText)
                .build()
        } else {
            Themis.ClarificationContextSpan.getDefaultInstance()
        }

    return Themis.AwaitingClarification
        .newBuilder()
        .setQuestion("Which interpretation did you mean?")
        .addAllOptions(options)
        .setContextSpan(contextSpan)
        .build()
}

internal fun buildResumeTokenForAwaiting(
    state: ResolverContext,
    awaiting: Themis.AwaitingClarification,
    tokenManager: HmacTokenManager,
): String {
    val nlpResp = state.parseState.nlpResponse
    return tokenManager.createResumeToken(
        question = state.recentTurns.lastOrNull()?.content ?: "",
        parseHash = nlpResp.traceId,
        domainCandidates =
            state.parseState.filteredSpans.associate {
                it.charStart.toString() to it.entityTypeCandidates
            },
        universalEntities =
            state.parseState.universalEntities.map {
                HmacTokenManager.UniversalEntityData(
                    rawText = it.rawText,
                    entityType = it.entityType.name,
                    normalizedValue = it.normalizedValue,
                    charStart = it.charStart,
                    charEnd = it.charEnd,
                )
            },
        ambiguityAsked = awaiting.question,
        roundCounter = state.parseState.roundCounter + 1,
        nlpLanguage = nlpResp.language,
        nlpLanguageConfidence = nlpResp.languageConfidence,
        nlpEngineUsed = nlpResp.engineUsed,
        nlpTokens = Json.encodeToString(nlpResp.tokens),
        nlpSentences = Json.encodeToString(nlpResp.sentences),
        nlpParagraphs = Json.encodeToString(nlpResp.paragraphs),
        nlpEntities = Json.encodeToString(nlpResp.entities),
        nlpTraceId = nlpResp.traceId,
        nlpElapsedMs = nlpResp.elapsedMs,
        nlpMessages = Json.encodeToString(nlpResp.messages),
        // Phase 3 Stage 3.4: carry the profile + offered alternates for resume-time
        // validation (the Layer-3 chip-pick check lands in Stage 3.6).
        profileAtIssue = state.profile.name,
        alternatesOffered =
            state.parseState.routingDecision
                ?.alternatesList
                ?.map { it.agentId.value } ?: emptyList(),
    )
}

fun decideHitlOrEmitStep(
    state: ResolverContext,
    tokenManager: HmacTokenManager,
    config: ResolverAppConfig,
): ResolverContext {
    logger.info {
        "node=decideHitlOrEmit confidence=${state.parseState.inferenceResult?.confidence} " +
            "rounds=${state.parseState.roundCounter}"
    }

    val result = state.parseState.inferenceResult
    if (result == null) {
        return state.copy(
            parseState =
                state.parseState.copy(
                    outcome = OutcomeState.Error,
                    terminalError = "No inference result",
                    lastNode = "decideHitlOrEmit",
                ),
        )
    }
    val threshold = config.hitl.confidenceThreshold
    val maxRounds = maxHitlRoundsFor(state.profile, config)

    // Phase 3 Stage 3.4: STRICT mode refuses on any blocker instead of asking.
    if (state.hitl == Themis.HitlProfile.STRICT) {
        val gaps = collectBlockers(state, threshold)
        if (gaps.isNotEmpty()) {
            val refusal =
                Themis.RefusalWithGaps
                    .newBuilder()
                    .addAllGaps(gaps)
                    .setRationale("STRICT mode: ${gaps.size} blocker(s) — refusing rather than asking")
                    .setTraceId(state.traceId)
                    .build()
            return state.copy(
                parseState =
                    state.parseState.copy(
                        outcome = OutcomeState.Refused,
                        terminalRefusal = refusal,
                        lastNode = "decideHitlOrEmit",
                    ),
            )
        }
    }

    if (result.confidence >= threshold || state.parseState.roundCounter >= maxRounds) {
        val resolution =
            Themis.Resolution
                .newBuilder()
                .setFunctionId(result.functionId)
                .setArgsJson(result.argsJson)
                .setConfidence(result.confidence)
                .setRationale(result.rationale)
                // Phase 3 Stage 3.2: carry the classifyIntentKind verdict.
                .setIntentKind(state.parseState.intentKind)
                // Phase 3 Stage 3.3: attach the routeToAgent decision when present.
                .apply { state.parseState.routingDecision?.let { setRouting(it) } }
                .build()
        return state.copy(
            parseState =
                state.parseState.copy(
                    outcome = OutcomeState.Resolved,
                    terminalResolution = resolution,
                    lastNode = "decideHitlOrEmit",
                ),
        )
    }

    val awaiting = buildAwaitingClarification(state, result)
    val token = buildResumeTokenForAwaiting(state, awaiting, tokenManager)
    return state.copy(
        parseState =
            state.parseState.copy(
                outcome = OutcomeState.AwaitingClarification,
                resumeToken = token,
                terminalAwaiting = awaiting,
                lastNode = "decideHitlOrEmit",
            ),
    )
}

/**
 * Phase 3 Stage 3.4 — accumulates the gaps that make STRICT mode refuse. Order
 * is structural-first: an unmapped entity or an unroutable request precedes a
 * bare ambiguity. `OUT_OF_DATA_SCOPE` is intentionally absent — undetectable
 * without a data layer (the GapKind remains for a later stage).
 */
fun collectBlockers(
    state: ResolverContext,
    threshold: Double,
): List<Themis.Gap> {
    val gaps = mutableListOf<Themis.Gap>()

    // ENTITY_UNMAPPED — a domain span with type candidates but no fuzzy resolution.
    state.parseState.filteredSpans.forEach { span ->
        if (span.entityTypeCandidates.isNotEmpty()) {
            val matched = state.parseState.fuzzyMatches[span.charStart.toString()].orEmpty()
            if (matched.isEmpty()) {
                gaps +=
                    gap(
                        Themis.GapKind.ENTITY_UNMAPPED,
                        "Could not resolve '${span.coveredText}' as ${span.entityTypeCandidates.first()}",
                    )
            }
        }
    }

    // CAPABILITY_UNAVAILABLE — routeToAgent produced no usable single agent.
    val routing = state.parseState.routingDecision
    if (routing != null && (routing.chosenAgentId.value.isBlank() || routing.needsUserPick)) {
        gaps += gap(Themis.GapKind.CAPABILITY_UNAVAILABLE, "No single agent can confidently handle this request")
    }

    // AMBIGUOUS_INTENT — low-confidence inference with no structural blocker above.
    val confidence = state.parseState.inferenceResult?.confidence ?: 0.0
    if (gaps.isEmpty() && confidence < threshold) {
        gaps += gap(Themis.GapKind.AMBIGUOUS_INTENT, "Could not confidently determine the intended action")
    }

    return gaps
}

private fun gap(
    kind: Themis.GapKind,
    description: String,
): Themis.Gap =
    Themis.Gap
        .newBuilder()
        .setKind(kind)
        .setDescription(description)
        .build()

/**
 * Phase 3 Stage 3.4 — CHAT_QUICK caps HITL at one round (fast path);
 * INVESTIGATION_DEEP uses the configured maximum (default 3).
 */
fun maxHitlRoundsFor(
    profile: Themis.Profile,
    config: ResolverAppConfig,
): Int = if (profile == Themis.Profile.INVESTIGATION_DEEP) config.hitl.maxRounds else 1
