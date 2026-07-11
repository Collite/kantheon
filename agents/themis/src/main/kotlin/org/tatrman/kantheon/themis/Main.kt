package org.tatrman.kantheon.themis

import org.tatrman.kantheon.themis.cache.ResolverCache
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.config.toLlmGatewayEndpoint
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.resolverConfigFrom
import org.tatrman.kantheon.themis.koog.ParseState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.koog.ThemisGraphDeps
import org.tatrman.kantheon.themis.koog.runThemisGraph
import org.tatrman.kantheon.themis.koog.NodeResult
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import com.typesafe.config.ConfigFactory
// SV-P0: these back a SPINE (nlp) response, whose messages use the relocated
// org.tatrman.common.v1.ResponseMessage (contracts §5) — not the agent common.
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Span
import org.tatrman.nlp.v1.Token
import org.tatrman.kantheon.themis.v1.Themis
import io.opentelemetry.api.common.AttributeKey
import org.tatrman.kantheon.themis.v1.freshOrNull
import org.tatrman.kantheon.themis.v1.resumeOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import shared.ktor.mcp.McpKtorConfig
import shared.ktor.mcp.installMcpKtorBase
import shared.ktor.mcp.safeMcpTool
import org.tatrman.kantheon.themis.client.NlpClient

private val logger = LoggerFactory.getLogger("resolver-agent")

private val DEFAULT_NLP_OPS =
    setOf(
        "TOKENIZE",
        "SENTENCE_SPLIT",
        "LEMMATIZE",
        "POS_TAG",
        "DEP_PARSE",
        "NER",
        "DETECT_LANGUAGE",
    )

fun main(): Unit =
    runBlocking {
        val config = ConfigFactory.load()
        val appConfig = resolverConfigFrom(config)

        val cache = ResolverCache(appConfig.cache)
        val tokenManager = HmacTokenManager(appConfig)
        val llmGatewayClient = LlmGatewayClient(appConfig.llmGateway.toLlmGatewayEndpoint())
        val fuzzyServiceClient = FuzzyServiceClient(appConfig.fuzzy)
        val capabilitiesClient =
            CapabilitiesReadClient(
                endpoint = "http://${appConfig.capabilities.host}:${appConfig.capabilities.port}",
            )
        // Fail-fast: refuse to start if the agent registry is empty/unreachable.
        assertRoutableAgentsAvailable(capabilitiesClient)
        val graphDeps =
            ThemisGraphDeps(llmGatewayClient, fuzzyServiceClient, tokenManager, appConfig, capabilitiesClient)

        val httpClient =
            HttpClient(ClientCIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = appConfig.nlp.timeoutMs
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = appConfig.nlp.timeoutMs
                }
            }
        val nlpClient = NlpClient(httpClient, "http://${appConfig.nlp.host}:${appConfig.nlp.port}")

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting down HTTP clients")
                llmGatewayClient.close()
                fuzzyServiceClient.close()
                capabilitiesClient.close()
                httpClient.close()
            },
        )

        val mcpKtorConfig =
            McpKtorConfig(
                serviceName = "resolver-agent",
                serverPort = appConfig.server.port,
                callLoggingConfig =
                    McpKtorConfig.CallLoggingConfig(
                        level = Level.INFO,
                        customFormat = { request ->
                            val method = request.httpMethod
                            val path = request.path()
                            val origin = request.headers[HttpHeaders.Origin] ?: "No-Origin"
                            val preflight = request.headers[HttpHeaders.AccessControlRequestMethod] ?: "N/A"
                            "-> $method $path | Origin: $origin | Preflight-Method: $preflight"
                        },
                    ),
            )

        logger.info("Resolver agent starting on port ${appConfig.server.port}")
        logger.info("NLP endpoint: ${appConfig.nlp.host}:${appConfig.nlp.port}")

        val appServerConfig =
            serverConfig {
                module {
                    installMcpKtorBase(mcpKtorConfig, ResolverOtel.openTelemetry)
                    routing {
                        route("v1") {
                            post("resolve") {
                                handleRestResolve(
                                    call = call,
                                    graphDeps = graphDeps,
                                    nlpClient = nlpClient,
                                    cache = cache,
                                    tokenManager = tokenManager,
                                    config = appConfig,
                                )
                            }
                        }
                    }
                    mcpStreamableHttp {
                        Server(
                            serverInfo = Implementation(name = "resolver-agent", version = "0.1.0"),
                            options =
                                ServerOptions(
                                    capabilities =
                                        ServerCapabilities(
                                            tools = ServerCapabilities.Tools(listChanged = false),
                                        ),
                                ),
                        ).also { server ->
                            server.addTool(resolveToolDef) { request ->
                                safeMcpTool("resolve", 120_000) {
                                    handleResolveTool(it, graphDeps, nlpClient, cache, tokenManager, appConfig)
                                }(request)
                            }
                        }
                    }
                }
            }

        embeddedServer(
            factory = CIO,
            rootConfig = appServerConfig,
            configure = {
                connectionIdleTimeoutSeconds = 180
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = appConfig.server.port
                        host = appConfig.server.host
                    },
                )
            },
        ).start(wait = true)
    }

private val resolveToolDef =
    Tool(
        name = "resolve",
        description = "Resolve a user question to an ERP function call using NLP + graph reasoning",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("question") {
                            put("type", "string")
                            put("description", "The user's natural-language question")
                        }
                        putJsonObject("conversation_id") {
                            put("type", "string")
                            put("description", "Optional conversation ID for tracing")
                        }
                        putJsonObject("locale") {
                            put("type", "string")
                            put("description", "Language locale (default cs)")
                        }
                        putJsonObject("resume_token") {
                            put("type", "string")
                            put("description", "Opaque token to resume a HITL clarification round")
                        }
                    },
                required = listOf("question"),
            ),
        outputSchema = null,
    )

private suspend fun handleResolveTool(
    request: CallToolRequest,
    graphDeps: ThemisGraphDeps,
    nlpClient: NlpClient,
    cache: ResolverCache,
    tokenManager: HmacTokenManager,
    config: ResolverAppConfig,
): CallToolResult {
    val args = request.arguments
    val question =
        args?.get("question")?.jsonPrimitive?.content
            ?: return errorResult("Missing required field: question")
    val conversationId =
        args.get("conversation_id")?.jsonPrimitive?.content
            ?: "resolver-${System.currentTimeMillis()}"
    val locale = args.get("locale")?.jsonPrimitive?.content ?: "cs"
    val resumeToken = args.get("resume_token")?.jsonPrimitive?.content

    logger.info(
        "resolve: conversationId={} question={} resumeToken={}",
        conversationId,
        question.take(80),
        resumeToken != null,
    )

    val startedMs = System.currentTimeMillis()
    return try {
        val ctx =
            buildResolverContext(question, conversationId, locale, resumeToken, nlpClient, cache, tokenManager)
                // MCP is a reduced surface — the tool schema exposes no registry /
                // profile / hitl — so disable routing rather than fire a Layer-2 LLM
                // call per request. Rich routing + refusal live on REST /v1/resolve.
                .copy(routingEnabled = false)
        val result = withTimeout(60_000) { runThemisGraph(ctx, graphDeps) }

        when (result) {
            is NodeResult.EmitResolution -> {
                val r = result.resolution
                val ctx = result.state
                val traceId = ctx.parseState.nlpResponse.traceId
                val elapsedMs = System.currentTimeMillis() - startedMs
                ResolverOtel
                    .requestCounter
                    .add(
                        1,
                        io.opentelemetry.api.common.Attributes.of(
                            AttributeKey.stringKey("outcome"),
                            "resolved",
                        ),
                    )
                ResolverOtel.confidenceHistogram.record((r.confidence * 100).toLong())
                ResolverOtel
                    .functionResolvedCounter
                    .add(
                        1,
                        io.opentelemetry.api.common.Attributes.of(
                            AttributeKey.stringKey("function_id"),
                            r.functionId,
                        ),
                    )
                val json =
                    buildJsonObject {
                        put("outcome", JsonPrimitive("resolved"))
                        put("function_id", JsonPrimitive(r.functionId))
                        put("confidence", JsonPrimitive(r.confidence))
                        put("rationale", JsonPrimitive(r.rationale))
                        put("args_json", JsonPrimitive(r.argsJson))
                        put("intent_kind", JsonPrimitive(r.intentKind.name)) // Phase 3 Stage 3.2
                        if (r.hasRouting()) { // Phase 3 Stage 3.3
                            put(
                                "routing",
                                buildJsonObject {
                                    put("chosen_agent_id", JsonPrimitive(r.routing.chosenAgentId.value))
                                    put("layer_hit", JsonPrimitive(r.routing.layerHit))
                                    put("confidence", JsonPrimitive(r.routing.confidence))
                                    put("needs_user_pick", JsonPrimitive(r.routing.needsUserPick))
                                },
                            )
                        }
                        put("trace_id", JsonPrimitive(traceId))
                        put("elapsed_ms", JsonPrimitive(elapsedMs))
                    }
                CallToolResult(content = listOf(TextContent(text = json.toString())), isError = false)
            }

            is NodeResult.EmitAwaiting -> {
                val a = result.awaiting
                val token = result.state.parseState.resumeToken ?: ""
                ResolverOtel
                    .requestCounter
                    .add(
                        1,
                        io.opentelemetry.api.common.Attributes.of(
                            AttributeKey.stringKey("outcome"),
                            "awaiting_clarification",
                        ),
                    )
                ResolverOtel.hitlRoundCounter.add(1)
                val json =
                    buildJsonObject {
                        put("outcome", JsonPrimitive("awaiting_clarification"))
                        put("question", JsonPrimitive(a.question))
                        put(
                            "options",
                            JsonArray(
                                a.optionsList.map { opt ->
                                    buildJsonObject {
                                        put("id", JsonPrimitive(opt.optionId))
                                        put("label", JsonPrimitive(opt.label))
                                        put("description", JsonPrimitive(opt.description))
                                    }
                                },
                            ),
                        )
                        put("resume_token", JsonPrimitive(token))
                        // Phase 3 Stage 3.2: surface a detected multi-question.
                        if (a.kindCase == Themis.AwaitingClarification.KindCase.MULTI_QUESTION) {
                            put(
                                "sub_questions",
                                JsonArray(a.multiQuestion.subQuestionsList.map { JsonPrimitive(it) }),
                            )
                            put("decomposition", JsonPrimitive(a.multiQuestion.decomposition.name))
                        }
                    }
                CallToolResult(content = listOf(TextContent(text = json.toString())), isError = false)
            }

            is NodeResult.EmitRefusal -> {
                // Phase 3 Stage 3.4: STRICT-mode terminal refusal.
                val refusalTraceId = result.state.parseState.nlpResponse.traceId
                val json =
                    buildJsonObject {
                        put("outcome", JsonPrimitive("refused"))
                        put("rationale", JsonPrimitive(result.refusal.rationale))
                        put(
                            "gaps",
                            JsonArray(
                                result.refusal.gapsList.map { g ->
                                    buildJsonObject {
                                        put("kind", JsonPrimitive(g.kind.name))
                                        put("description", JsonPrimitive(g.description))
                                        if (g.hasSuggestedAction()) {
                                            put("suggested_action", JsonPrimitive(g.suggestedAction))
                                        }
                                    }
                                },
                            ),
                        )
                        put("trace_id", JsonPrimitive(refusalTraceId))
                    }
                CallToolResult(content = listOf(TextContent(text = json.toString())), isError = false)
            }

            is NodeResult.Continue -> errorResult("Unexpected continue state after graph run")
            is NodeResult.Error -> errorResult(result.message)
        }
    } catch (e: Exception) {
        logger.error("handleResolveTool failed", e)
        errorResult(e.message ?: "Unknown error")
    }
}

private fun errorResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = """{"error":"$message"}""")),
        isError = true,
    )

private suspend fun handleRestResolve(
    call: ApplicationCall,
    graphDeps: ThemisGraphDeps,
    nlpClient: NlpClient,
    cache: ResolverCache,
    tokenManager: HmacTokenManager,
    config: ResolverAppConfig,
) {
    val logger = org.slf4j.LoggerFactory.getLogger("resolver-agent.rest")
    val startedMs = System.currentTimeMillis()
    val resolverRequest = call.receive(org.tatrman.kantheon.themis.v1.Themis.ResolveRequest::class)
    val conversationId =
        resolverRequest.conversationId.takeIf { it.isNotEmpty() }
            ?: "resolver-${System.currentTimeMillis()}"

    val question: String
    var locale: String = resolverRequest.freshOrNull?.locale?.takeIf { it.isNotEmpty() } ?: "cs"
    val resumeToken: String?

    when (resolverRequest.inputCase) {
        Themis.ResolveRequest.InputCase.FRESH -> {
            question = resolverRequest.freshOrNull?.text
                ?: throw IllegalArgumentException("Missing required field: text")
            locale = resolverRequest.freshOrNull?.locale?.takeIf { it.isNotEmpty() } ?: "cs"
            resumeToken = null
        }

        Themis.ResolveRequest.InputCase.RESUME -> {
            val resume =
                resolverRequest.resumeOrNull
                    ?: throw IllegalArgumentException("Missing required field: resume")
            resumeToken = resume.token
            val payload =
                tokenManager.verifyAndDecode(resume.token)
                    ?: throw IllegalArgumentException("Invalid resume token")
            question = payload.question
        }

        else -> throw IllegalArgumentException("Missing required input: fresh or resume")
    }

    logger.info(
        "REST resolve: conversationId={} question={} resumeToken={}",
        conversationId,
        question.take(80),
        resumeToken != null,
    )

    val mode = normalizeMode(resolverRequest.mode)
    val registry = projectRegistryForMode(resolverRequest.registry, mode)
    // Phase 3 Stage 3.3: thread the routing profile + Layer-0 hint from the request.
    val profile =
        if (resolverRequest.profile == Themis.Profile.PROFILE_UNSPECIFIED) {
            Themis.Profile.CHAT_QUICK
        } else {
            resolverRequest.profile
        }
    val routingHint = if (resolverRequest.hasRoutingHint()) resolverRequest.routingHint else null
    // Phase 3 Stage 3.4: per-request HITL profile (UNSPECIFIED → INTERACTIVE).
    val hitl =
        if (resolverRequest.context.hitl == Themis.HitlProfile.HITL_PROFILE_UNSPECIFIED) {
            Themis.HitlProfile.INTERACTIVE
        } else {
            resolverRequest.context.hitl
        }
    val ctx =
        buildResolverContext(question, conversationId, locale, resumeToken, nlpClient, cache, tokenManager)
            .copy(mode = mode, registry = registry, profile = profile, routingHint = routingHint, hitl = hitl)
    val result = withTimeout(60_000) { runThemisGraph(ctx, graphDeps) }

    val nlp = ctx.parseState.nlpResponse

    val parseResponse =
        org.tatrman.nlp.v1.AnalyzeResponse
            .newBuilder()
            .setLanguage(nlp.language)
            .setLanguageConfidence(nlp.languageConfidence)
            .setEngineUsed(nlp.engineUsed)
            .setTraceId(nlp.traceId)
            .setElapsedMs(nlp.elapsedMs)
            .addAllTokens(
                nlp.tokens.map { t ->
                    Token
                        .newBuilder()
                        .setText(t.text)
                        .setCharStart(t.charStart)
                        .setCharEnd(t.charEnd)
                        .setLemma(t.lemma)
                        .setUpos(t.upos)
                        .setXpos(t.xpos)
                        .setDepHead(t.depHead)
                        .setDepRelation(t.depRelation)
                        .putAllFeats(t.feats)
                        .build()
                },
            ).addAllSentences(
                nlp.sentences.map { s ->
                    Span
                        .newBuilder()
                        .setCharStart(s.charStart)
                        .setCharEnd(s.charEnd)
                        .build()
                },
            ).addAllParagraphs(
                nlp.paragraphs.map { p ->
                    Span
                        .newBuilder()
                        .setCharStart(p.charStart)
                        .setCharEnd(p.charEnd)
                        .build()
                },
            ).addAllEntities(
                nlp.entities.map { e ->
                    NerEntity
                        .newBuilder()
                        .setText(e.text)
                        .setLabel(e.label)
                        .setCharStart(e.charStart)
                        .setCharEnd(e.charEnd)
                        .setNormalizedValue(e.normalizedValue)
                        .setSourceEngine(e.sourceEngine)
                        .build()
                },
            ).addAllMessages(
                nlp.messages.map { m ->
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(severityOf(m.severity))
                        .setCode(m.code)
                        .setHumanMessage(m.message)
                        .build()
                },
            ).build()

    val traceId = nlp.traceId
    val elapsedMs = System.currentTimeMillis() - startedMs

    val responseBuilder =
        org.tatrman.kantheon.themis.v1.Themis.ResolveResponse
            .newBuilder()
            .setTraceId(traceId)
            .setElapsedMs(elapsedMs)
            .setParse(parseResponse)

    when (result) {
        is NodeResult.EmitResolution -> {
            val r = result.resolution
            responseBuilder.resolutionBuilder
                .setFunctionId(r.functionId)
                .setConfidence(r.confidence)
                .setRationale(r.rationale)
                .setArgsJson(r.argsJson)
                .addAllBindings(r.bindingsList)
                .setIntentKind(r.intentKind) // Phase 3 Stage 3.2
            if (r.hasRouting()) { // Phase 3 Stage 3.3
                responseBuilder.resolutionBuilder.routing = r.routing
                ResolverOtel.recordRoutingDecision( // Phase 3 Stage 3.6 (T4)
                    layer = r.routing.layerHit,
                    agentId = r.routing.chosenAgentId.value,
                    confidence = r.routing.confidence,
                )
            }
        }

        is NodeResult.EmitAwaiting -> {
            val a = result.awaiting
            responseBuilder.awaitingBuilder
                .setQuestion(a.question)
                .setContextSpan(
                    org.tatrman.kantheon.themis.v1.Themis.ClarificationContextSpan
                        .newBuilder()
                        .setCharStart(a.contextSpan.charStart)
                        .setCharEnd(a.contextSpan.charEnd)
                        .setCoveredText(a.contextSpan.coveredText),
                )
            a.optionsList.forEach { opt ->
                responseBuilder.awaitingBuilder.addOptions(
                    org.tatrman.kantheon.themis.v1.Themis.ClarificationOption
                        .newBuilder()
                        .setOptionId(opt.optionId)
                        .setLabel(opt.label)
                        .setDescription(opt.description),
                )
            }
            // Phase 3 Stage 3.2: carry the multi-question verdict through the oneof.
            if (a.kindCase == org.tatrman.kantheon.themis.v1.Themis.AwaitingClarification.KindCase.MULTI_QUESTION) {
                responseBuilder.awaitingBuilder.multiQuestion = a.multiQuestion
            }
        }

        is NodeResult.EmitRefusal -> {
            // Phase 3 Stage 3.4: STRICT-mode terminal refusal.
            responseBuilder.refusalBuilder
                .addAllGaps(result.refusal.gapsList)
                .setRationale(result.refusal.rationale)
                .setTraceId(traceId)
            result.refusal.gapsList.forEach {
                // Phase 3 Stage 3.6 (T4)
                ResolverOtel.recordRefusal(it.kind.name)
            }
        }

        is NodeResult.Continue -> throw IllegalStateException("Unexpected continue state after graph run")
        is NodeResult.Error -> throw IllegalStateException("Graph error: ${result.message}")
    }

    call.respond(io.ktor.http.HttpStatusCode.OK, responseBuilder.build())
}

private suspend fun buildResolverContext(
    question: String,
    conversationId: String,
    locale: String,
    resumeToken: String?,
    nlpClient: NlpClient,
    cache: ResolverCache,
    tokenManager: HmacTokenManager,
): ResolverContext {
    val ops = if (locale.isNotEmpty()) DEFAULT_NLP_OPS - "DETECT_LANGUAGE" else DEFAULT_NLP_OPS
    val opsKey = ops.sorted().joinToString(",")

    val nlpResult: NlpAnalyzeResult =
        if (resumeToken != null) {
            val payload = tokenManager.verifyAndDecode(resumeToken)
            if (payload != null) {
                logger.debug("NLP extracted from resume token")
                tokenManager.nlpAnalyzeResultFromPayload(payload)
            } else {
                val cached = cache.getNlpCached(cache.nlpCacheKey(question, locale, opsKey))
                if (cached != null) {
                    logger.debug("NLP cache hit for question")
                    NlpAnalyzeResult(
                        language = cached.language,
                        languageConfidence = cached.languageConfidence,
                        engineUsed = cached.engineUsed,
                        tokens = Json.decodeFromString(cached.tokens),
                        sentences = Json.decodeFromString(cached.sentences),
                        paragraphs = Json.decodeFromString(cached.paragraphs),
                        entities = Json.decodeFromString(cached.entities),
                        traceId = cached.traceId,
                        elapsedMs = cached.elapsedMs,
                        messages = Json.decodeFromString(cached.messages),
                    )
                } else {
                    nlpClient.analyze(text = question, language = locale, ops = ops)
                }
            }
        } else {
            val cached = cache.getNlpCached(cache.nlpCacheKey(question, locale, opsKey))
            if (cached != null) {
                logger.debug("NLP cache hit for question")
                NlpAnalyzeResult(
                    language = cached.language,
                    languageConfidence = cached.languageConfidence,
                    engineUsed = cached.engineUsed,
                    tokens = Json.decodeFromString(cached.tokens),
                    sentences = Json.decodeFromString(cached.sentences),
                    paragraphs = Json.decodeFromString(cached.paragraphs),
                    entities = Json.decodeFromString(cached.entities),
                    traceId = cached.traceId,
                    elapsedMs = cached.elapsedMs,
                    messages = Json.decodeFromString(cached.messages),
                )
            } else {
                val result = nlpClient.analyze(text = question, language = locale, ops = ops)
                cache.putNlpCached(
                    cache.nlpCacheKey(question, locale, opsKey),
                    ResolverCache.NlpCacheEntry(
                        text = question,
                        lang = locale,
                        ops = opsKey,
                        language = result.language,
                        languageConfidence = result.languageConfidence,
                        engineUsed = result.engineUsed,
                        tokens = Json.encodeToString(result.tokens),
                        sentences = Json.encodeToString(result.sentences),
                        paragraphs = Json.encodeToString(result.paragraphs),
                        entities = Json.encodeToString(result.entities),
                        traceId = result.traceId,
                        elapsedMs = result.elapsedMs,
                        messages = Json.encodeToString(result.messages),
                    ),
                )
                result
            }
        }

    val roundCounter =
        if (resumeToken != null) {
            tokenManager.verifyAndDecode(resumeToken)?.roundCounter ?: 0
        } else {
            0
        }

    val parseState =
        ParseState(
            nlpResponse = nlpResult,
            roundCounter = roundCounter,
            resumeToken = resumeToken,
        )

    val turn =
        Themis.Turn
            .newBuilder()
            .setRole("user")
            .setContent(question)
            .build()

    return ResolverContext(
        requestId = conversationId,
        conversationId = conversationId,
        locale = locale,
        parseState = parseState,
        registry = Themis.Registry.getDefaultInstance(),
        recentEntities = emptyList(),
        recentTurns = listOf(turn),
    )
}

internal fun normalizeMode(mode: Themis.ResolveMode): Themis.ResolveMode =
    when (mode) {
        Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY -> Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY
        else -> Themis.ResolveMode.RESOLVE_MODE_NORMAL
    }

/**
 * In ENTITIES_ONLY mode the resolver must NOT consult caller-supplied function_specs
 * (per contracts §2.1) — but it still needs entity_types to scope the fuzzy matcher
 * namespace. Strip function_specs only; keep the rest of the registry verbatim.
 */
internal fun projectRegistryForMode(
    registry: Themis.Registry,
    mode: Themis.ResolveMode,
): Themis.Registry =
    if (mode == Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY) {
        registry.toBuilder().clearFunctionSpecs().build()
    } else {
        registry
    }

private fun severityOf(s: String): Severity =
    when (s.lowercase()) {
        "info", "i" -> Severity.INFO
        "warning", "warn", "w" -> Severity.WARNING
        "error", "err", "e" -> Severity.ERROR
        else -> Severity.INFO
    }
