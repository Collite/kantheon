package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.koog.FuzzyCandidate
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }

/**
 * Stage 2.3 T4 — body of the `fuzzyMatchSpans` node.
 *
 * For every filtered domain span, calls the fuzzy-matching service in parallel
 * (one [kotlinx.coroutines.async] per span) and accumulates the per-span
 * candidate map keyed by `span.charStart.toString()`. Behaviour is preserved
 * verbatim from the original `ThemisGraphDispatch.fuzzyMatchSpans`:
 *  - empty `entityTypeCandidates` → skip the span (empty list)
 *  - prefer the namespace of the first candidate's `EntityTypeSpec`; fall
 *    back to the registry's first entry if absent
 *  - hard-coded `algorithm = "TATRMAN"` and `limit = 5`
 *
 * [namespaceFor] is exposed for unit tests so the fallback ordering can be
 * asserted without spinning up a `FuzzyServiceClient` mock.
 */

fun namespaceFor(
    span: DomainSpan,
    entityTypes: List<Themis.EntityTypeSpec>,
): String? {
    if (span.entityTypeCandidates.isEmpty()) return null
    val entityTypeRef = span.entityTypeCandidates.first()
    val direct = entityTypes.find { it.entityTypeRef == entityTypeRef }?.fuzzyMatcherNamespace
    if (!direct.isNullOrEmpty()) return direct
    val fallback = entityTypes.firstOrNull()?.fuzzyMatcherNamespace
    return fallback?.takeIf { it.isNotEmpty() }
}

suspend fun fuzzyMatchSingleSpan(
    fuzzy: FuzzyServiceClient,
    span: DomainSpan,
    entityTypes: List<Themis.EntityTypeSpec>,
    limit: Int = CHAT_QUICK_FUZZY_LIMIT,
): List<FuzzyCandidate> {
    if (span.entityTypeCandidates.isEmpty()) return emptyList()
    val namespace =
        namespaceFor(span, entityTypes) ?: run {
            logger.warn { "No fuzzyMatcherNamespace found for entityTypeRef: ${span.entityTypeCandidates.first()}" }
            return emptyList()
        }
    return fuzzy.match(
        category = namespace,
        name = span.coveredText,
        algorithm = "TATRMAN",
        limit = limit,
    )
}

// Phase 3 Stage 3.4: per-profile candidate breadth. INVESTIGATION_DEEP widens
// the net (more alternates for deeper resolution); CHAT_QUICK stays lean.
const val CHAT_QUICK_FUZZY_LIMIT = 3
const val INVESTIGATION_DEEP_FUZZY_LIMIT = 10

fun fuzzyLimitFor(profile: Themis.Profile): Int =
    if (profile == Themis.Profile.INVESTIGATION_DEEP) INVESTIGATION_DEEP_FUZZY_LIMIT else CHAT_QUICK_FUZZY_LIMIT

suspend fun fuzzyMatchSpansStep(
    state: ResolverContext,
    fuzzy: FuzzyServiceClient,
): ResolverContext =
    coroutineScope {
        val filteredSpans = state.parseState.filteredSpans
        val entityTypes = state.registry.entityTypesList
        val limit = fuzzyLimitFor(state.profile)
        val deferred =
            filteredSpans.map { span ->
                async { span.charStart.toString() to fuzzyMatchSingleSpan(fuzzy, span, entityTypes, limit) }
            }
        val matches = mutableMapOf<String, List<FuzzyCandidate>>()
        for (d in deferred) {
            val (key, candidates) = d.await()
            matches[key] = candidates
        }
        state.copy(
            parseState =
                state.parseState.copy(
                    fuzzyMatches = matches,
                    lastNode = "fuzzyMatchSpans",
                ),
        )
    }
