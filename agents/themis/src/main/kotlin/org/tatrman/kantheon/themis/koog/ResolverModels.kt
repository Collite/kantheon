package org.tatrman.kantheon.themis.koog

import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.v1.Themis
import java.time.Instant

data class ParseState(
    val nlpResponse: NlpAnalyzeResult,
    val universalEntities: List<UniversalEntityNormalized> = emptyList(),
    val domainSpans: List<DomainSpan> = emptyList(),
    val filteredSpans: List<DomainSpan> = emptyList(),
    val fuzzyMatches: Map<String, List<FuzzyCandidate>> = emptyMap(),
    val inferenceResult: InferenceResult? = null,
    // Phase 3 Stage 3.2: analytical intent classified by classifyIntentKind
    // (after extractUniversal). Threaded into Resolution.intent_kind by
    // decideHitlOrEmit. UNSPECIFIED until the node runs (e.g. resume path).
    val intentKind: Themis.IntentKind = Themis.IntentKind.INTENT_KIND_UNSPECIFIED,
    // Phase 3 Stage 3.3: routeToAgent verdict (null for INVESTIGATION_DEEP or
    // before the node runs). decideHitlOrEmit attaches it to Resolution.routing.
    val routingDecision: Themis.RoutingDecision? = null,
    val outcome: OutcomeState = OutcomeState.Pending,
    val roundCounter: Int = 0,
    val resumeToken: String? = null,
    val lastNode: String? = null,
    // Terminal carriers — set by the final nodes (decideHitlOrEmit /
    // entitiesOnlyAssemble) so the Koog graph can flow ResolverContext end-
    // to-end without heterogeneous node output types. `themisGraphRun`
    // packs these into a `NodeResult` after `AIAgent.run` returns.
    val terminalResolution: org.tatrman.kantheon.themis.v1.Themis.Resolution? = null,
    val terminalAwaiting: org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification? = null,
    // Phase 3 Stage 3.4: STRICT-mode terminal refusal.
    val terminalRefusal: org.tatrman.kantheon.themis.v1.Themis.RefusalWithGaps? = null,
    val terminalError: String? = null,
)

enum class OutcomeState {
    Pending,
    Parsing,
    Resolved,
    AwaitingClarification,
    Refused,
    Error,
}

data class UniversalEntityNormalized(
    val rawText: String,
    val entityType: Themis.UniversalEntityType,
    val normalizedValue: String,
    val sourceEngine: String,
    val charStart: Int,
    val charEnd: Int,
)

data class DomainSpan(
    val charStart: Int,
    val charEnd: Int,
    val coveredText: String,
    val pos: String,
    val depHead: Int,
    val depRelation: String,
    val entityTypeCandidates: List<String> = emptyList(),
)

data class FuzzyCandidate(
    val fuzzyId: String,
    val fuzzyLabel: String,
    val score: Double,
    val entityTypeRef: String,
)

@kotlinx.serialization.Serializable
data class FilterIndexEntry(
    val index: Int,
    val entityTypes: List<String>,
)

data class InferenceResult(
    val functionId: String,
    val argsJson: String,
    val bindings: List<Themis.EntityBinding>,
    val confidence: Double,
    val alternatives: List<org.tatrman.kantheon.themis.v1.Themis.InferenceAlternative>,
    val rationale: String,
)

data class ResolverContext(
    val requestId: String = "",
    val conversationId: String,
    val registry: Themis.Registry = Themis.Registry.getDefaultInstance(),
    val locale: String = "cs",
    val recentEntities: List<Themis.EntityContext> = emptyList(),
    val recentTurns: List<Themis.Turn> = emptyList(),
    val parseState: ParseState,
    val resolvedAt: Instant = Instant.now(),
    val traceId: String = "",
    // RESOLVE_MODE_ENTITIES_ONLY skips function-id inference. UNSPECIFIED → treat as NORMAL.
    val mode: Themis.ResolveMode = Themis.ResolveMode.RESOLVE_MODE_NORMAL,
    // Phase 3 Stage 3.3: routing profile + Layer-0 override, threaded from the
    // ResolveRequest. PROFILE_UNSPECIFIED is treated as CHAT_QUICK (routeToAgent runs);
    // INVESTIGATION_DEEP skips routing. routingHint null when unset.
    val profile: Themis.Profile = Themis.Profile.CHAT_QUICK,
    val routingHint: AgentId? = null,
    // Phase 3 Stage 3.4: per-request HITL profile. UNSPECIFIED → INTERACTIVE.
    val hitl: Themis.HitlProfile = Themis.HitlProfile.INTERACTIVE,
    // Reduced surfaces (the MCP `resolve` tool) set this false: they carry no
    // registry/profile, so routeToAgent would only burn a Layer-2 LLM call per
    // request with no usable input. The REST /v1/resolve path leaves it true.
    val routingEnabled: Boolean = true,
)
