package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.koog.DomainSpan
import org.tatrman.kantheon.themis.koog.FilterIndexEntry
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }
private val filterJson = Json { ignoreUnknownKeys = true }

/**
 * Stage 2.3 T4 — body of the `filterRelevantSpans` node (CHEAP-tier LLM call).
 *
 * **Prompt parity invariant.** [buildFilterPrompt] reproduces the ai-platform
 * Resolver prompt byte-for-byte. The Stage 2.4 eval gate will catch any
 * drift; do not edit the template here.
 *
 * **StructureFixingParser status.** The plan T4 spec suggests wrapping the
 * call with Koog's `StructureFixingParser` (validated in the Stage 2.1 spike).
 * Adoption is deferred for this PR — the existing prompt returns a top-level
 * JSON **array** and Koog's `JsonStructure.create<T>()` assumes a JSON object.
 * Adopting it cleanly requires either a prompt rewrite (forbidden by the
 * parity invariant above) or a custom `Structure<List<FilterIndexEntry>, *>`
 * subclass. Tracking that as a Stage 2.4 follow-up; for now we preserve the
 * Resolver's silent-fallback semantics on parse failure.
 *
 * [parseFilterResponse] preserves silent-fallback intentionally so eval-gate
 * traces match the pre-migration baseline.
 */

fun buildFilterPrompt(
    spans: List<DomainSpan>,
    entityTypes: List<Themis.EntityTypeSpec>,
    @Suppress("UNUSED_PARAMETER") locale: String,
): String {
    val spansText: String =
        buildString {
            spans.forEachIndexed { idx, span ->
                append("  [$idx] '${span.coveredText}' (pos=${span.pos}, dep=${span.depRelation})\n")
            }
        }.trimEnd()
    val entityTypesText =
        entityTypes.joinToString("\n") { et ->
            "  - ${et.entityTypeRef}: ${et.description} (fuzzyMatcher=${et.fuzzyMatcherNamespace})"
        }
    return """
        |You are a domain relevance filter for a Czech/English ERP question resolver.
        |Given these candidate text spans:
        |$spansText
        |And these domain entity types:
        |$entityTypesText
        |Return ONLY a JSON array where each element is an object with:
        |  - "index": the span index (integer)
        |  - "entityTypes": array of likely entityTypeRef values for that span
        |Example: [{"index":0,"entityTypes":["customerId"]}, {"index":2,"entityTypes":["invoiceId","orderId"]}]
        |Only include spans that are likely domain entities.
        """.trimMargin()
}

fun parseFilterResponse(
    response: String,
    spans: List<DomainSpan>,
    entityTypes: List<Themis.EntityTypeSpec>,
): List<DomainSpan> =
    try {
        val cleaned =
            response
                .trim()
                .replace("```json", "")
                .replace("```", "")
                .trim()
        val entries: List<FilterIndexEntry> = filterJson.decodeFromString(cleaned)
        val validRefs = entityTypes.map { it.entityTypeRef }.toSet()
        entries
            .filter { entry -> entry.entityTypes.any { it in validRefs } }
            .mapNotNull { entry ->
                spans.getOrNull(entry.index)?.copy(
                    entityTypeCandidates = entry.entityTypes.filter { it in validRefs },
                )
            }
    } catch (e: Exception) {
        logger.warn { "Failed to parse filter response: $response" }
        spans
    }

/** Silent-fallback wrapper around the CHEAP LLM call. Matches ai-platform's
 *  `ThemisGraphDispatch.callLlmCheap` semantics: any gateway failure returns `"[]"`,
 *  which `parseFilterResponse` then turns into "no filter applied". */
suspend fun callFilterLlm(
    llm: LlmGatewayClient,
    prompt: String,
): String =
    llm
        .complete(
            prompt = prompt,
            model = "haiku",
            temperature = 0.0,
        ).getOrElse {
            logger.error { "LLM cheap call failed: ${it.message}" }
            "[]"
        }

suspend fun filterRelevantSpansStep(
    state: ResolverContext,
    llm: LlmGatewayClient,
): ResolverContext {
    val candidateSpans = state.parseState.domainSpans
    val entityTypes = state.registry.entityTypesList
    val prompt = buildFilterPrompt(candidateSpans, entityTypes, state.locale)
    logger.debug { "filterRelevantSpans prompt: $prompt" }
    val llmResponse = callFilterLlm(llm, prompt)
    val filteredSpans = parseFilterResponse(llmResponse, candidateSpans, entityTypes)
    return state.copy(
        parseState =
            state.parseState.copy(
                filteredSpans = filteredSpans,
                lastNode = "filterRelevantSpans",
            ),
    )
}
