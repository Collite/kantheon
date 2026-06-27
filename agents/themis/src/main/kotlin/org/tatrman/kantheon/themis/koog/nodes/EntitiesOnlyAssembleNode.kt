package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.koog.FuzzyCandidate
import org.tatrman.kantheon.themis.koog.OutcomeState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis
import java.util.Locale

private val logger = KotlinLogging.logger { }

/** Below this fuzzy score we never present a candidate (ENTITIES_ONLY mode). */
const val ENTITIES_ONLY_CANDIDATE_THRESHOLD: Double = 0.5

/** If top-1 and top-2 are both viable AND within this gap, treat as ambiguous
 *  even when both are above the top-1 confidence threshold. Prevents silently
 *  binding the wrong one of two near-identical matches. */
const val ENTITIES_ONLY_AMBIGUITY_GAP: Double = 0.05

/**
 * Stage 2.3 T5 — body of `entitiesOnlyAssemble`.
 *
 * ENTITIES_ONLY mode: skip function-id inference, just resolve which fuzzy
 * candidate each domain span binds to. If any span has multiple viable
 * candidates that are too close to call (the [ENTITIES_ONLY_AMBIGUITY_GAP]
 * window), emit [NodeResult.EmitAwaiting] for the first such ambiguity
 * with a resume token. Otherwise emit [NodeResult.EmitResolution] with
 * the resolved bindings and confidence = `min(domainScores)`.
 *
 * Verbatim from ai-platform Resolver — threshold constants, option text
 * ("Which entity did you mean for …"), and the `min(score)` confidence
 * heuristic all preserved for eval-gate parity.
 */

fun buildDomainBinding(
    span: DomainSpan,
    top: FuzzyCandidate,
    rest: List<FuzzyCandidate>,
): Themis.EntityBinding {
    val domain =
        Themis.DomainEntityBinding
            .newBuilder()
            .setEntityTypeRef(top.entityTypeRef)
            .setRawText(span.coveredText)
            .setResolvedId(top.fuzzyId)
            .setResolvedLabel(top.fuzzyLabel)
            .addAllAlternatives(
                rest.map { alt ->
                    Themis.FuzzyCandidate
                        .newBuilder()
                        .setFuzzyId(alt.fuzzyId)
                        .setFuzzyLabel(alt.fuzzyLabel)
                        .setScore(alt.score)
                        .build()
                },
            ).build()
    return Themis.EntityBinding
        .newBuilder()
        .setSpanStart(span.charStart)
        .setSpanEnd(span.charEnd)
        .setDomain(domain)
        .build()
}

fun buildEntitiesOnlyAwaiting(ambiguity: Pair<DomainSpan, List<FuzzyCandidate>>): Themis.AwaitingClarification {
    val (span, candidates) = ambiguity
    val options =
        candidates.take(5).mapIndexed { idx, cand ->
            Themis.ClarificationOption
                .newBuilder()
                .setOptionId("ent_${idx + 1}")
                .setLabel(cand.fuzzyLabel)
                .setDescription("score=${"%.2f".format(Locale.ROOT, cand.score)}")
                .build()
        }
    return Themis.AwaitingClarification
        .newBuilder()
        .setQuestion("Which entity did you mean for \"${span.coveredText}\"?")
        .addAllOptions(options)
        .setContextSpan(
            Themis.ClarificationContextSpan
                .newBuilder()
                .setCharStart(span.charStart)
                .setCharEnd(span.charEnd)
                .setCoveredText(span.coveredText)
                .build(),
        ).build()
}

internal fun buildEntitiesOnlyResumeToken(
    state: ResolverContext,
    awaitingQuestion: String,
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
        ambiguityAsked = awaitingQuestion,
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
    )
}

fun entitiesOnlyAssembleStep(
    state: ResolverContext,
    tokenManager: HmacTokenManager,
    confidenceThreshold: Double,
): ResolverContext {
    logger.info { "node=entitiesOnlyAssemble conversationId=${state.conversationId}" }

    val ambiguousSpans = mutableListOf<Pair<DomainSpan, List<FuzzyCandidate>>>()
    val resolvedDomainBindings = mutableListOf<Themis.EntityBinding>()

    for (filteredSpan in state.parseState.filteredSpans) {
        val candidates =
            state.parseState.fuzzyMatches[filteredSpan.charStart.toString()]
                ?.sortedByDescending { it.score }
                .orEmpty()
        val viable = candidates.filter { it.score >= ENTITIES_ONLY_CANDIDATE_THRESHOLD }
        val closeTie =
            viable.size >= 2 &&
                (viable[0].score - viable[1].score) < ENTITIES_ONLY_AMBIGUITY_GAP
        when {
            viable.isEmpty() -> {
                // No usable fuzzy candidate — leave unbound.
            }
            closeTie -> ambiguousSpans.add(filteredSpan to viable)
            viable.size == 1 || viable[0].score >= confidenceThreshold -> {
                val top = viable[0]
                resolvedDomainBindings.add(buildDomainBinding(filteredSpan, top, viable.drop(1)))
            }
            else -> ambiguousSpans.add(filteredSpan to viable)
        }
    }

    val universalBindings =
        state.parseState.universalEntities.map { ue ->
            Themis.EntityBinding
                .newBuilder()
                .setSpanStart(ue.charStart)
                .setSpanEnd(ue.charEnd)
                .setUniversal(
                    Themis.UniversalEntityBinding
                        .newBuilder()
                        .setEntityType(ue.entityType)
                        .setRawText(ue.rawText)
                        .setNormalizedValue(ue.normalizedValue)
                        .setSourceEngine(ue.sourceEngine),
                ).build()
        }

    if (ambiguousSpans.isNotEmpty()) {
        val awaiting = buildEntitiesOnlyAwaiting(ambiguousSpans.first())
        val token = buildEntitiesOnlyResumeToken(state, awaiting.question, tokenManager)
        return state.copy(
            parseState =
                state.parseState.copy(
                    outcome = OutcomeState.AwaitingClarification,
                    resumeToken = token,
                    terminalAwaiting = awaiting,
                    lastNode = "entitiesOnlyAssemble",
                ),
        )
    }

    val allBindings = universalBindings + resolvedDomainBindings
    val domainScores =
        resolvedDomainBindings.map { binding ->
            val candidates =
                state.parseState.fuzzyMatches[binding.spanStart.toString()].orEmpty()
            candidates.maxOfOrNull { it.score } ?: 1.0
        }
    val confidence = domainScores.minOrNull() ?: 1.0
    val rationale =
        allBindings
            .mapNotNull { b ->
                when {
                    b.hasDomain() -> b.domain.resolvedLabel.ifEmpty { b.domain.rawText }
                    b.hasUniversal() -> b.universal.normalizedValue.ifEmpty { b.universal.rawText }
                    else -> null
                }
            }.filter { it.isNotEmpty() }
            .joinToString(", ")

    val resolution =
        Themis.Resolution
            .newBuilder()
            .setFunctionId("")
            .setArgsJson("{}")
            .setConfidence(confidence)
            .setRationale(rationale)
            .addAllBindings(allBindings)
            .build()

    return state.copy(
        parseState =
            state.parseState.copy(
                outcome = OutcomeState.Resolved,
                terminalResolution = resolution,
                lastNode = "entitiesOnlyAssemble",
            ),
    )
}
