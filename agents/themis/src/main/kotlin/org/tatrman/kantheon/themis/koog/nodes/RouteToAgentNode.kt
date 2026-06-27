package org.tatrman.kantheon.themis.koog.nodes

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.capabilities.client.CapabilitiesUnreachableException
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }
private val routeJson = Json { ignoreUnknownKeys = true }

/**
 * Phase 3 Stage 3.3 — `routeToAgent` four-layer cascade (runs after
 * jointInference, before decideHitlOrEmit; skipped entirely for
 * INVESTIGATION_DEEP).
 *
 *  - **Layer 0** — `routing_hint` honoured verbatim (confidence 1.0).
 *  - **Layer 1** — rule-based scoring over the agent registry; a clear winner
 *    (gap > ε and score ≥ min) routes with no LLM call.
 *  - **Layer 2** — CHEAP-tier LLM disambiguation among the top candidates;
 *    confidence ≥ 0.7 routes.
 *  - **Layer 3** — `needs_user_pick` with the top-3 alternates as RoutingPickChips.
 *
 * The capabilities client returns raw `JsonObject` (camelCase REST mirror), so
 * agents are parsed by hand. Layer-1 weights are hand-tuned guesses — Stage 3.5
 * re-tunes against the eval corpus.
 */
data class RouteInput(
    val profile: Themis.Profile,
    val routingHint: AgentId?,
    val intentKind: Themis.IntentKind,
    val question: String,
    val entityTypes: List<String>,
    val conversationExcerpt: String = "",
)

data class RouteOutput(
    val skip: Boolean,
    val decision: Themis.RoutingDecision?,
    val relevantCapabilities: List<String> = emptyList(),
) {
    companion object {
        fun skip() = RouteOutput(skip = true, decision = null)

        fun of(
            decision: Themis.RoutingDecision,
            relevant: List<String>,
        ) = RouteOutput(skip = false, decision = decision, relevantCapabilities = relevant)
    }
}

data class LayerWeights(
    val intentKindMatch: Double = 0.5,
    val entitiesInDomain: Double = 0.4,
    val analyticalToInvestigator: Double = 0.4,
    val crossDomainToInvestigator: Double = 0.3,
    val capabilityMatch: Double = 0.2,
    val epsilon: Double = 0.2,
    val minScore: Double = 0.5,
    val layer2AcceptConfidence: Double = 0.7,
) {
    companion object {
        fun default() = LayerWeights()
    }
}

/** An agent manifest, parsed from the capabilities-mcp camelCase REST JSON. */
data class RouterAgent(
    val agentId: String,
    val agentKind: String,
    val intentKindsSupported: List<String>,
    val areaEntities: List<String>,
    val capabilityRefs: List<String>,
    val nonRoutable: Boolean,
    val descriptionForRouter: String,
    val exampleQuestions: List<String>,
    val counterExamples: List<String>,
)

private data class ScoredAgent(
    val agent: RouterAgent,
    val score: Double,
)

private val ANALYTICAL_INTENTS =
    setOf(Themis.IntentKind.RCA, Themis.IntentKind.FORECAST, Themis.IntentKind.SIMULATION)

suspend fun routeToAgent(
    input: RouteInput,
    capabilities: CapabilitiesReadClient,
    llm: LlmGatewayClient,
    weights: LayerWeights = LayerWeights.default(),
): RouteOutput {
    if (input.profile == Themis.Profile.INVESTIGATION_DEEP) return RouteOutput.skip()

    // Layer 0 — explicit override. Zero-cost: short-circuits before any
    // capabilities-mcp round-trip — relevant-capabilities and agent scoring are
    // moot once the caller has pinned the target agent.
    input.routingHint?.takeIf { it.value.isNotBlank() }?.let { hint ->
        return RouteOutput.of(
            decision(hint.value, confidence = 1.0, rationale = "routing_hint honoured", layer = 0),
            emptyList(),
        )
    }

    val relevant = computeRelevantCapabilities(input, capabilities)
    val agents = loadRoutableAgents(capabilities)
    if (agents.isEmpty()) {
        return RouteOutput.of(
            decision("", confidence = 0.0, rationale = "no routable agents", layer = 1, needsUserPick = true),
            relevant,
        )
    }

    val scored =
        agents
            .map { ScoredAgent(it, scoreAgent(it, input, agents, relevant, weights)) }
            .sortedByDescending { it.score }

    // Layer 1 — clear rule-based winner.
    layer1(scored, weights)?.let { return RouteOutput.of(it, relevant) }

    // Layer 2 — cheap-LLM disambiguation.
    val llmResult = layer2(input, scored, llm)
    if (llmResult.confidence >= weights.layer2AcceptConfidence && llmResult.chosenAgentId.isNotBlank()) {
        return RouteOutput.of(
            decision(
                llmResult.chosenAgentId,
                confidence = llmResult.confidence,
                rationale = llmResult.rationale,
                layer = 2,
            ),
            relevant,
        )
    }

    // Layer 3 — user pick.
    return RouteOutput.of(layer3(llmResult), relevant)
}

private fun scoreAgent(
    agent: RouterAgent,
    input: RouteInput,
    agents: List<RouterAgent>,
    relevant: List<String>,
    w: LayerWeights,
): Double {
    var score = 0.0
    if (input.intentKind.name in agent.intentKindsSupported) score += w.intentKindMatch
    if (input.intentKind == Themis.IntentKind.PROCEDURAL &&
        agent.areaEntities.isNotEmpty() &&
        input.entityTypes.isNotEmpty() &&
        agent.areaEntities.containsAll(input.entityTypes)
    ) {
        score += w.entitiesInDomain
    }
    if (input.intentKind in ANALYTICAL_INTENTS && agent.agentKind == "INVESTIGATOR") {
        score += w.analyticalToInvestigator
    }
    if (agent.agentKind == "INVESTIGATOR" && spansMultipleDomains(input.entityTypes, agents)) {
        score += w.crossDomainToInvestigator
    }
    if (agent.capabilityRefs.isNotEmpty() && relevant.containsAll(agent.capabilityRefs)) {
        score += w.capabilityMatch
    }
    return score
}

/** True when the resolved entity types are owned by more than one AREA_QA agent. */
private fun spansMultipleDomains(
    entityTypes: List<String>,
    agents: List<RouterAgent>,
): Boolean {
    if (entityTypes.isEmpty()) return false
    val owners =
        agents
            .filter { it.agentKind == "AREA_QA" && it.areaEntities.any { e -> e in entityTypes } }
            .map { it.agentId }
            .toSet()
    return owners.size > 1
}

private fun layer1(
    scored: List<ScoredAgent>,
    w: LayerWeights,
): Themis.RoutingDecision? {
    val top = scored.firstOrNull() ?: return null
    val second = scored.getOrNull(1)
    val gap = top.score - (second?.score ?: 0.0)
    if (gap > w.epsilon && top.score >= w.minScore) {
        return decision(
            top.agent.agentId,
            confidence = top.score.coerceAtMost(1.0),
            rationale = "rule-based: top=${top.score}, second=${second?.score ?: 0.0}, gap=$gap",
            layer = 1,
        )
    }
    return null
}

private suspend fun layer2(
    input: RouteInput,
    scored: List<ScoredAgent>,
    llm: LlmGatewayClient,
): RoutingLlmResult {
    val top5 = scored.take(5).map { it.agent }
    val prompt = buildLayer2Prompt(input, top5)
    val response =
        llm
            .complete(prompt = prompt, model = "haiku", temperature = 0.0)
            .getOrElse {
                logger.error { "routeToAgent Layer-2 LLM call failed: ${it.message}" }
                """{"chosen_agent_id":"${top5.firstOrNull()?.agentId ?: ""}","confidence":0.0,"rationale":"LLM unavailable","alternates":[]}"""
            }
    return parseRoutingResponse(response, fallbackAgentId = top5.firstOrNull()?.agentId ?: "")
}

private fun layer3(llmResult: RoutingLlmResult): Themis.RoutingDecision {
    val top3 =
        (
            listOf(RoutingLlmAlternate(llmResult.chosenAgentId, llmResult.confidence, llmResult.rationale)) +
                llmResult.alternates
        ).filter { it.agentId.isNotBlank() }
            .distinctBy { it.agentId }
            .take(3)
    val builder =
        Themis.RoutingDecision
            .newBuilder()
            .setNeedsUserPick(true)
            .setRationale("Layer-2 confidence ${llmResult.confidence} below threshold — user pick required")
            .setLayerHit(3)
    top3.forEach { alt ->
        builder.addAlternates(
            Themis.AgentAlternate
                .newBuilder()
                .setAgentId(AgentId.newBuilder().setValue(alt.agentId).build())
                .setScore(alt.score)
                .setWhy(alt.why)
                .build(),
        )
    }
    return builder.build()
}

private suspend fun computeRelevantCapabilities(
    input: RouteInput,
    capabilities: CapabilitiesReadClient,
): List<String> {
    val wanted =
        (input.entityTypes.map { it.lowercase() } + input.intentKind.name.lowercase()).toSet()
    return try {
        val entries = capabilities.list()["entries"]?.jsonArray ?: return emptyList()
        entries
            .map { it.jsonObject }
            .filter { it["kind"]?.jsonPrimitive?.content == "tool" }
            .mapNotNull { it["tool"]?.jsonObject }
            .filter { tool ->
                tool["searchTags"]?.jsonArray?.any { tag -> tag.jsonPrimitive.content.lowercase() in wanted } == true
            }.mapNotNull { it["capabilityId"]?.jsonPrimitive?.content }
            .distinct()
    } catch (e: CapabilitiesUnreachableException) {
        logger.warn { "relevant-capabilities lookup failed: ${e.message}" }
        emptyList()
    }
}

private suspend fun loadRoutableAgents(capabilities: CapabilitiesReadClient): List<RouterAgent> {
    val agentsJson = capabilities.listAgents()["agents"]?.jsonArray ?: return emptyList()
    return agentsJson
        .map { parseRouterAgent(it.jsonObject) }
        .filterNot { it.nonRoutable }
}

private fun parseRouterAgent(obj: JsonObject): RouterAgent =
    RouterAgent(
        agentId = obj.str("agentId"),
        agentKind = obj.str("agentKind"),
        intentKindsSupported = obj.strList("intentKindsSupported"),
        areaEntities = obj.strList("areaEntities"),
        capabilityRefs = obj.strList("capabilityRefs"),
        nonRoutable = obj["nonRoutable"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        descriptionForRouter = obj.str("descriptionForRouter"),
        exampleQuestions = obj.strList("exampleQuestions"),
        counterExamples = obj.strList("counterExamples"),
    )

private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

private fun JsonObject.strList(key: String): List<String> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

fun buildLayer2Prompt(
    input: RouteInput,
    candidates: List<RouterAgent>,
): String {
    val candidateBlock =
        candidates.joinToString("\n") { a ->
            buildString {
                append("- ${a.agentId} (${a.agentKind}): ${a.descriptionForRouter}\n")
                if (a.exampleQuestions.isNotEmpty()) {
                    append("    examples: ${a.exampleQuestions.joinToString("; ")}\n")
                }
                if (a.counterExamples.isNotEmpty()) {
                    append("    do NOT route here for: ${a.counterExamples.joinToString("; ")}")
                }
            }
        }
    return """
        |Route the question to the single most appropriate agent.
        |Question: "${input.question}"
        |Intent kind (classified upstream): ${input.intentKind.name}
        |Resolved entity types: ${input.entityTypes.joinToString(", ").ifBlank { "(none)" }}
        |Conversation excerpt: ${input.conversationExcerpt.ifBlank { "(none)" }}
        |
        |Candidate agents:
        |$candidateBlock
        |
        |Return a JSON object:
        |{"chosen_agent_id":"<id>","confidence":0.0-1.0,"rationale":"<one sentence>","alternates":[{"agent_id":"<id>","score":0.0-1.0,"why":"<reason>"}]}
        |Choose exactly one chosen_agent_id from the candidates; list up to 3 alternates.
        |Be conservative: if multiple agents plausibly fit, set confidence <= 0.7.
        """.trimMargin()
}

/** Parsed Layer-2 LLM verdict. Snake_case keys to match the prompt's schema. */
data class RoutingLlmResult(
    val chosenAgentId: String,
    val confidence: Double,
    val rationale: String,
    val alternates: List<RoutingLlmAlternate>,
)

data class RoutingLlmAlternate(
    val agentId: String,
    val score: Double,
    val why: String,
)

fun parseRoutingResponse(
    response: String,
    fallbackAgentId: String,
): RoutingLlmResult {
    val cleaned =
        response
            .trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
    return try {
        val obj = routeJson.decodeFromString<JsonObject>(cleaned)
        RoutingLlmResult(
            chosenAgentId = obj["chosen_agent_id"]?.jsonPrimitive?.content ?: fallbackAgentId,
            confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            rationale = obj["rationale"]?.jsonPrimitive?.content ?: "",
            alternates =
                obj["alternates"]?.jsonArray?.map { alt ->
                    val a = alt.jsonObject
                    RoutingLlmAlternate(
                        agentId = a["agent_id"]?.jsonPrimitive?.content ?: "",
                        score = a["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        why = a["why"]?.jsonPrimitive?.content ?: "",
                    )
                } ?: emptyList(),
        )
    } catch (e: Exception) {
        logger.error(e) { "Failed to parse routing response: $response" }
        RoutingLlmResult(fallbackAgentId, 0.0, "parse error: ${e.message}", emptyList())
    }
}

private fun decision(
    agentId: String,
    confidence: Double,
    rationale: String,
    layer: Int,
    needsUserPick: Boolean = false,
): Themis.RoutingDecision =
    Themis.RoutingDecision
        .newBuilder()
        .setChosenAgentId(AgentId.newBuilder().setValue(agentId).build())
        .setConfidence(confidence)
        .setRationale(rationale)
        .setLayerHit(layer)
        .setNeedsUserPick(needsUserPick)
        .build()

/**
 * Graph step: assembles [RouteInput] from the [ResolverContext], runs the
 * cascade, and stows the decision on `ParseState.routingDecision` for
 * decideHitlOrEmit to attach to the Resolution. INVESTIGATION_DEEP leaves it null.
 */
suspend fun routeToAgentStep(
    state: ResolverContext,
    capabilities: CapabilitiesReadClient,
    llm: LlmGatewayClient,
    weights: LayerWeights = LayerWeights.default(),
): ResolverContext {
    // Reduced surfaces (the MCP `resolve` tool) disable routing: they carry no
    // registry/profile, so a full cascade would only fire a Layer-2 LLM call per
    // request with no usable input. Leave Resolution.routing unset.
    if (!state.routingEnabled) {
        return state.copy(parseState = state.parseState.copy(lastNode = "routeToAgent"))
    }
    val input =
        RouteInput(
            profile = state.profile,
            routingHint = state.routingHint,
            intentKind = state.parseState.intentKind,
            question =
                state.recentTurns.lastOrNull()?.content
                    ?: state.parseState.nlpResponse.tokens
                        .joinToString(" ") { it.text },
            entityTypes = resolvedEntityTypes(state),
            conversationExcerpt = state.recentTurns.takeLast(2).joinToString(" | ") { it.content },
        )
    // Routing is best-effort: a registry hiccup must not fail an otherwise-good
    // resolution. On error, degrade to no routing decision.
    val decision =
        try {
            routeToAgent(input, capabilities, llm, weights).decision
        } catch (e: Exception) {
            logger.warn(e) { "routeToAgent failed; degrading to no routing decision" }
            null
        }
    logger.debug { "routeToAgent → layer=${decision?.layerHit}" }
    return state.copy(
        parseState = state.parseState.copy(routingDecision = decision, lastNode = "routeToAgent"),
    )
}

/** Domain entity-type refs resolved so far (filtered spans + fuzzy matches). */
private fun resolvedEntityTypes(state: ResolverContext): List<String> {
    val fromSpans = state.parseState.filteredSpans.flatMap { it.entityTypeCandidates }
    val fromFuzzy =
        state.parseState.fuzzyMatches.values
            .flatten()
            .map { it.entityTypeRef }
    return (fromSpans + fromFuzzy).filter { it.isNotBlank() }.distinct()
}
