package org.tatrman.kantheon.themis.koog

import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.client.NlpEntity
import org.tatrman.kantheon.themis.client.NlpToken

/**
 * Phase 02 — Resolver entities-only mode.
 *
 * Asserts:
 *  - ENTITIES_ONLY skips the joint-inference (intent classifier) LLM call.
 *  - function_id / args_json are empty in the resolution.
 *  - bindings carry the universal + domain entities.
 *  - Ambiguous fuzzy candidates still produce AwaitingClarification with a resume token.
 *  - Registry is ignored; no registered function bleeds into the resolution.
 */
class ResolverEntitiesOnlySpec :
    StringSpec({

        "ENTITIES_ONLY -- top-1 fuzzy match -- emits Resolution with empty function_id and populated bindings" {
            val fuzzy =
                mockk<FuzzyServiceClient> {
                    coEvery { match(any(), any(), any(), any()) } returns
                        listOf(
                            FuzzyCandidate(
                                fuzzyId = "strediska/5",
                                fuzzyLabel = "DF ADNAK",
                                score = 0.98,
                                entityTypeRef = "stredisko",
                            ),
                        )
                }
            val llm = recordingLlm(filterResponse = """[{"index":0,"entityTypes":["stredisko"]}]""")
            val graph = makeGraphForEntitiesOnly(fuzzyClient = fuzzy, llmClient = llm.client)

            val state =
                entitiesOnlyContext(
                    tokens =
                        listOf(
                            nounTok("středisko", 24, 33),
                            propnTok("DF ADNAK", 34, 42),
                        ),
                    entities =
                        listOf(
                            NlpEntity(
                                text = "2026.03",
                                label = "DATE",
                                charStart = 53,
                                charEnd = 60,
                                normalizedValue = "2026-03",
                                sourceEngine = "stanza",
                            ),
                        ),
                )

            val result = graph.run(state)

            val resolution = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolution.resolution.functionId shouldBe ""
            resolution.resolution.argsJson shouldBe "{}"
            resolution.resolution.bindingsList.size shouldBeGreaterThanOrEqual 2

            val labels =
                resolution.resolution.bindingsList.map { binding ->
                    when {
                        binding.hasDomain() -> binding.domain.resolvedLabel
                        binding.hasUniversal() -> binding.universal.normalizedValue
                        else -> ""
                    }
                }
            labels shouldContain "DF ADNAK"
            labels shouldContain "2026-03"

            // The joint-inference (sonnet) LLM call MUST NOT have been made.
            coVerify(exactly = 0) {
                llm.client.complete(any(), any(), eq("sonnet"), any(), any())
            }
        }

        "ENTITIES_ONLY -- ambiguous fuzzy candidates -- emits AwaitingClarification with resume token" {
            val fuzzy =
                mockk<FuzzyServiceClient> {
                    coEvery { match(any(), any(), any(), any()) } returns
                        listOf(
                            FuzzyCandidate(
                                fuzzyId = "strediska/5",
                                fuzzyLabel = "DF ADNAK",
                                score = 0.70,
                                entityTypeRef = "stredisko",
                            ),
                            FuzzyCandidate(
                                fuzzyId = "strediska/7",
                                fuzzyLabel = "DF ADNAK 2",
                                score = 0.68,
                                entityTypeRef = "stredisko",
                            ),
                        )
                }
            val llm = recordingLlm(filterResponse = """[{"index":0,"entityTypes":["stredisko"]}]""")
            val graph = makeGraphForEntitiesOnly(fuzzyClient = fuzzy, llmClient = llm.client)

            val state =
                entitiesOnlyContext(
                    tokens = listOf(nounTok("středisko", 0, 9), propnTok("DF ADNAK", 10, 18)),
                )

            val result = graph.run(state)

            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.awaiting.optionsList.size shouldBeGreaterThanOrEqual 2
            awaiting.state.parseState.resumeToken shouldBe "mock-resume-token"
            // Parse-passthrough invariant — the NLP response is still embedded in state.
            awaiting.state.parseState.nlpResponse.traceId shouldNotBe ""
        }

        "ENTITIES_ONLY -- close-tied high-scoring candidates -- still treated as ambiguous" {
            // Both candidates are above the top-1 threshold (0.75) but separated by less
            // than ENTITIES_ONLY_AMBIGUITY_GAP (0.05). Silently binding the top one
            // would mean a coin-flip every time the fuzzy ranker hesitates between two
            // near-identical labels — surface the choice instead.
            val fuzzy =
                mockk<FuzzyServiceClient> {
                    coEvery { match(any(), any(), any(), any()) } returns
                        listOf(
                            FuzzyCandidate(
                                fuzzyId = "strediska/5",
                                fuzzyLabel = "DF ADNAK",
                                score = 0.95,
                                entityTypeRef = "stredisko",
                            ),
                            FuzzyCandidate(
                                fuzzyId = "strediska/7",
                                fuzzyLabel = "DF ADNAK 2",
                                score = 0.94,
                                entityTypeRef = "stredisko",
                            ),
                        )
                }
            val llm = recordingLlm(filterResponse = """[{"index":0,"entityTypes":["stredisko"]}]""")
            val graph = makeGraphForEntitiesOnly(fuzzyClient = fuzzy, llmClient = llm.client)

            val state =
                entitiesOnlyContext(
                    tokens = listOf(nounTok("středisko", 0, 9), propnTok("DF ADNAK", 10, 18)),
                )

            val result = graph.run(state)

            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.awaiting.optionsList.size shouldBeGreaterThanOrEqual 2
        }

        "ENTITIES_ONLY -- top-1 LLM filter call IS made (haiku)" {
            // Symmetric to the sonnet-not-called assertion: the filter step before
            // jointInference still runs, so callers get filteredSpans + fuzzy bindings.
            val fuzzy =
                mockk<FuzzyServiceClient> {
                    coEvery { match(any(), any(), any(), any()) } returns
                        listOf(
                            FuzzyCandidate(
                                fuzzyId = "strediska/5",
                                fuzzyLabel = "DF ADNAK",
                                score = 0.98,
                                entityTypeRef = "stredisko",
                            ),
                        )
                }
            val llm = recordingLlm(filterResponse = """[{"index":0,"entityTypes":["stredisko"]}]""")
            val graph = makeGraphForEntitiesOnly(fuzzyClient = fuzzy, llmClient = llm.client)
            val state =
                entitiesOnlyContext(
                    tokens = listOf(nounTok("středisko", 0, 9), propnTok("DF ADNAK", 10, 18)),
                )

            graph.run(state)

            coVerify(exactly = 1) {
                llm.client.complete(any(), any(), eq("haiku"), any(), any())
            }
        }

        "ENTITIES_ONLY -- populated Registry is ignored" {
            val fuzzy =
                mockk<FuzzyServiceClient> {
                    coEvery { match(any(), any(), any(), any()) } returns
                        listOf(
                            FuzzyCandidate(
                                fuzzyId = "strediska/5",
                                fuzzyLabel = "DF ADNAK",
                                score = 0.98,
                                entityTypeRef = "stredisko",
                            ),
                        )
                }
            val llm = recordingLlm(filterResponse = """[{"index":0,"entityTypes":["stredisko"]}]""")
            val graph = makeGraphForEntitiesOnly(fuzzyClient = fuzzy, llmClient = llm.client)

            // Pass a registry with a fake function, but the request-level mode is ENTITIES_ONLY,
            // which the REST/MCP boundary strips. Here we emulate the in-graph contract — graph
            // never reads functionSpecs in this mode.
            val registry =
                Themis.Registry
                    .newBuilder()
                    .addFunctionSpecs(
                        Themis.FunctionSpec
                            .newBuilder()
                            .setFunctionId("fakeFunctionShouldNotAppear")
                            .setDescription("placeholder"),
                    ).addEntityTypes(
                        Themis.EntityTypeSpec
                            .newBuilder()
                            .setEntityTypeRef("stredisko")
                            .setDescription("Účetní středisko")
                            .setFuzzyMatcherNamespace("strediska"),
                    ).build()

            val state =
                entitiesOnlyContext(
                    tokens = listOf(nounTok("středisko", 0, 9), propnTok("DF ADNAK", 10, 18)),
                ).copy(registry = registry)

            val result = graph.run(state)

            val resolution = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolution.resolution.functionId shouldBe ""
            resolution.resolution.rationale shouldNotContain "fakeFunctionShouldNotAppear"
            resolution.resolution.bindingsList
                .map { if (it.hasDomain()) it.domain.entityTypeRef else "" } shouldNotContain
                "fakeFunctionShouldNotAppear"
        }
    })

// -------------------------------------------------------------------------
// Test helpers — local to this spec
// -------------------------------------------------------------------------

private data class RecordingLlm(
    val client: LlmGatewayClient,
)

private fun recordingLlm(filterResponse: String): RecordingLlm {
    val client = mockk<LlmGatewayClient>()
    coEvery { client.complete(any(), any(), eq("haiku"), any(), any()) } returns Result.success(filterResponse)
    coEvery { client.complete(any(), any(), eq("sonnet"), any(), any()) } returns
        Result.success("""{"functionId":"shouldNotBeCalled","argsJson":"{}","confidence":0.0,"rationale":""}""")
    return RecordingLlm(client)
}

private suspend fun ThemisGraphDeps.run(state: ResolverContext): NodeResult = runThemisGraph(state, this)

private fun makeGraphForEntitiesOnly(
    fuzzyClient: FuzzyServiceClient,
    llmClient: LlmGatewayClient,
): ThemisGraphDeps {
    val tokenManager = mockk<HmacTokenManager>()
    every { tokenManager.verifyAndDecode(any()) } returns null
    every {
        tokenManager.createResumeToken(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    } returns "mock-resume-token"
    return ThemisGraphDeps(
        llm = llmClient,
        fuzzy = fuzzyClient,
        tokenManager = tokenManager,
        config = entitiesOnlyConfig(),
        // ENTITIES_ONLY skips joint/route, so capabilities are never read.
        capabilities = mockk<CapabilitiesReadClient>(),
        intentRules =
            org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
                .load(),
    )
}

private fun entitiesOnlyConfig() =
    ResolverAppConfig(
        server = ServerConfig(port = 7171, host = "0.0.0.0"),
        nlp = EndpointConfig(host = "localhost", port = 8000, timeoutMs = 30_000),
        fuzzy = EndpointConfig(host = "localhost", port = 7143, timeoutMs = 15_000),
        llmGateway = EndpointConfig(host = "localhost", port = 8090, timeoutMs = 60_000),
        hmac = HmacConfig(secretKey = "test-key-for-unit-tests-only-32b"),
        cache = CacheConfig(nlpLruSize = 100, resolutionLruSize = 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig(corpusPath = "eval/corpus/seed.jsonl"),
    )

private fun entitiesOnlyContext(
    tokens: List<NlpToken> = emptyList(),
    entities: List<NlpEntity> = emptyList(),
) = ResolverContext(
    requestId = "test-req",
    conversationId = "test-conv",
    registry =
        Themis.Registry
            .newBuilder()
            .addEntityTypes(
                Themis.EntityTypeSpec
                    .newBuilder()
                    .setEntityTypeRef("stredisko")
                    .setDescription("Účetní středisko")
                    .setFuzzyMatcherNamespace("strediska"),
            ).build(),
    locale = "cs",
    mode = Themis.ResolveMode.RESOLVE_MODE_ENTITIES_ONLY,
    parseState =
        ParseState(
            nlpResponse =
                NlpAnalyzeResult(
                    language = "cs",
                    languageConfidence = 0.99,
                    engineUsed = "stanza",
                    tokens = tokens,
                    sentences = emptyList(),
                    paragraphs = emptyList(),
                    entities = entities,
                    traceId = "trace-eo-1",
                    elapsedMs = 10,
                    messages = emptyList(),
                ),
        ),
    recentTurns =
        listOf(
            Themis.Turn
                .newBuilder()
                .setRole("user")
                .setContent("Vypiš účetní záznamy pro středisko DF ADNAK za období 2026.03")
                .build(),
        ),
)

private fun nounTok(
    text: String,
    start: Int,
    end: Int,
) = NlpToken(
    text = text,
    charStart = start,
    charEnd = end,
    lemma = text.lowercase(),
    upos = "NOUN",
    xpos = "_",
    feats = emptyMap(),
    depHead = 0,
    depRelation = "obj",
)

private fun propnTok(
    text: String,
    start: Int,
    end: Int,
) = NlpToken(
    text = text,
    charStart = start,
    charEnd = end,
    lemma = text,
    upos = "PROPN",
    xpos = "_",
    feats = emptyMap(),
    depHead = 0,
    depRelation = "nsubj",
)
