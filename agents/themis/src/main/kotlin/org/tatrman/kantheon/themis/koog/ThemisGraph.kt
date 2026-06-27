package org.tatrman.kantheon.themis.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.llm.client.LlmGatewayPromptExecutor
import org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
import org.tatrman.kantheon.themis.koog.nodes.classifyIntentKindStep
import org.tatrman.kantheon.themis.koog.nodes.routeToAgentStep
import org.tatrman.kantheon.themis.koog.nodes.decideHitlOrEmitStep
import org.tatrman.kantheon.themis.koog.nodes.decodeResumeTokenStep
import org.tatrman.kantheon.themis.koog.nodes.detectLangAndParseStep
import org.tatrman.kantheon.themis.koog.nodes.detectMultiQuestionStep
import org.tatrman.kantheon.themis.koog.nodes.entitiesOnlyAssembleStep
import org.tatrman.kantheon.themis.koog.nodes.extractUniversalStep
import org.tatrman.kantheon.themis.koog.nodes.filterRelevantSpansStep
import org.tatrman.kantheon.themis.koog.nodes.fuzzyMatchSpansStep
import org.tatrman.kantheon.themis.koog.nodes.jointInferenceStep
import org.tatrman.kantheon.themis.koog.nodes.proposeDomainSpansStep
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis

private val logger = KotlinLogging.logger { }

/**
 * The Themis resolution graph — the single production execution path (the legacy
 * `ThemisGraphDispatch` dispatch loop was deleted 2026-06-21; node-pipeline tests
 * now call the step functions directly).
 *
 * Topology (Phase 3): `docs/architecture/themis/architecture.md` §6.2 —
 *
 *  nodeStart ─[resume]─► decode ─[bad token]─► nodeFinish (Error)
 *           └─[fresh]─► detect ─► detectMulti ─[multi]─► nodeFinish (AwaitingClarification)
 *
 *  …extract ─► classify ─► propose ─► filter ─► fuzzy ─[ENTITIES_ONLY]─► entitiesOnly ─► nodeFinish
 *                                                      └─[NORMAL]─► joint ─► route ─► decide ─► nodeFinish
 *
 * Strategy I/O is `ResolverContext → ResolverContext`. The terminal nodes
 * (`decideHitlOrEmit` / `entitiesOnlyAssemble` / `decodeResumeToken` on
 * failure) encode the outcome in `state.parseState.terminal*` fields; the
 * outer call site reads those and maps to [NodeResult] via
 * [ResolverContext.toNodeResult].
 */
fun buildThemisGraph(
    llm: LlmGatewayClient,
    fuzzy: FuzzyServiceClient,
    tokenManager: HmacTokenManager,
    config: ResolverAppConfig,
    intentRules: IntentKindRules,
    capabilities: CapabilitiesReadClient,
): AIAgentGraphStrategy<ResolverContext, ResolverContext> =
    strategy("themis") {
        val detect by node<ResolverContext, ResolverContext>("detectLangAndParse") { state ->
            detectLangAndParseStep(state)
        }
        val detectMulti by node<ResolverContext, ResolverContext>("detectMultiQuestion") { state ->
            detectMultiQuestionStep(state)
        }
        val decode by node<ResolverContext, ResolverContext>("decodeTokenAndApplyChoice") { state ->
            decodeResumeTokenStep(state, tokenManager)
        }
        val extract by node<ResolverContext, ResolverContext>("extractUniversal") { state ->
            extractUniversalStep(state)
        }
        val classify by node<ResolverContext, ResolverContext>("classifyIntentKind") { state ->
            classifyIntentKindStep(state, intentRules, llm)
        }
        val propose by node<ResolverContext, ResolverContext>("proposeDomainSpans") { state ->
            proposeDomainSpansStep(state)
        }
        val filter by node<ResolverContext, ResolverContext>("filterRelevantSpans") { state ->
            filterRelevantSpansStep(state, llm)
        }
        val fuzzyNode by node<ResolverContext, ResolverContext>("fuzzyMatchSpans") { state ->
            fuzzyMatchSpansStep(state, fuzzy)
        }
        val joint by node<ResolverContext, ResolverContext>("jointInference") { state ->
            jointInferenceStep(state, llm)
        }
        val route by node<ResolverContext, ResolverContext>("routeToAgent") { state ->
            routeToAgentStep(state, capabilities, llm)
        }
        val decide by node<ResolverContext, ResolverContext>("decideHitlOrEmit") { state ->
            decideHitlOrEmitStep(state, tokenManager, config)
        }
        val entitiesOnly by node<ResolverContext, ResolverContext>("entitiesOnlyAssemble") { state ->
            entitiesOnlyAssembleStep(state, tokenManager, config.hitl.confidenceThreshold)
        }

        // Entry: resume vs fresh.
        edge(nodeStart forwardTo decode onCondition { state -> state.parseState.resumeToken != null })
        edge(nodeStart forwardTo detect onCondition { state -> state.parseState.resumeToken == null })

        // Fresh path screens for compound questions before extraction. A multi-
        // question short-circuits to nodeFinish carrying AwaitingClarification.
        edge(detect forwardTo detectMulti)
        edge(detectMulti forwardTo nodeFinish onCondition { state -> state.parseState.terminalAwaiting != null })
        edge(detectMulti forwardTo extract onCondition { state -> state.parseState.terminalAwaiting == null })

        // Resume path: a bad/expired token short-circuits to nodeFinish carrying
        // the terminal error; otherwise the resolved clarification flows to extract.
        edge(decode forwardTo nodeFinish onCondition { state -> state.parseState.terminalError != null })
        edge(decode forwardTo extract onCondition { state -> state.parseState.terminalError == null })

        // Classify analytical intent, then the linear middle.
        edge(extract forwardTo classify)
        edge(classify forwardTo propose)
        edge(propose forwardTo filter)
        edge(filter forwardTo fuzzyNode)

        // Mode-dependent terminal.
        edge(
            fuzzyNode forwardTo entitiesOnly onCondition { state ->
                state.mode == Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY
            },
        )
        edge(
            fuzzyNode forwardTo joint onCondition { state ->
                state.mode != Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY
            },
        )
        // NORMAL mode: classify-intent → route → decide. routeToAgent internally
        // no-ops for INVESTIGATION_DEEP (leaves Resolution.routing unset).
        edge(joint forwardTo route)
        edge(route forwardTo decide)

        edge(decide forwardTo nodeFinish)
        edge(entitiesOnly forwardTo nodeFinish)
    }

/**
 * Runs the Koog ThemisGraph end-to-end via [AIAgent.run] and maps the final
 * [ResolverContext] into a [NodeResult] consumed by the HTTP/MCP resolve handlers.
 *
 * Constructs a fresh [AIAgent] per call — the agent itself is stateless and
 * lightweight. Heavy lifting lives in the per-step closures, which capture
 * their dependencies (clients, token manager, config).
 */
suspend fun runThemisGraph(
    state: ResolverContext,
    deps: ThemisGraphDeps,
): NodeResult {
    val executor = LlmGatewayPromptExecutor(deps.llm)
    val strategy =
        buildThemisGraph(
            deps.llm,
            deps.fuzzy,
            deps.tokenManager,
            deps.config,
            deps.intentRules,
            deps.capabilities,
        )
    val agent =
        AIAgent<ResolverContext, ResolverContext>(
            promptExecutor = executor,
            strategy = strategy,
            agentConfig = themisAgentConfig(),
        )
    // Catch node failures and surface them as NodeResult.Error (the production
    // contract the deleted ThemisGraphDispatch.run upheld), rather than letting a
    // gateway/parse exception escape the resolve handler.
    return try {
        agent.run(state).toNodeResult()
    } catch (e: Exception) {
        logger.error(e) { "Themis graph execution failed" }
        NodeResult.Error(e.message ?: "Unknown error", state)
    }
}

/** Dependencies the Koog graph captures via the strategy closure. */
data class ThemisGraphDeps(
    val llm: LlmGatewayClient,
    val fuzzy: FuzzyServiceClient,
    val tokenManager: HmacTokenManager,
    val config: ResolverAppConfig,
    val capabilities: CapabilitiesReadClient,
    val intentRules: IntentKindRules = IntentKindRules.load(),
)
