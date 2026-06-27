package org.tatrman.kantheon.themis.koog

import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.config.toLlmGatewayEndpoint
import org.tatrman.kantheon.themis.token.HmacTokenManager
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.tatrman.kantheon.themis.v1.Themis
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.client.NlpEntity
import org.tatrman.kantheon.themis.client.NlpSpan
import org.tatrman.kantheon.themis.client.NlpToken

class ResolverIntegrationTest : StringSpec() {
    private val llmMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
    private val fuzzyMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

    init {
        beforeSpec {
            llmMock.start()
            fuzzyMock.start()
        }
        afterSpec {
            llmMock.stop()
            fuzzyMock.stop()
        }
        beforeEach {
            llmMock.resetAll()
            fuzzyMock.resetAll()
        }

        "happy path — high confidence question resolves to EmitResolution" {
            stubLlmFilter("""[{"index":0,"entityTypes":["customerId"]},{"index":1,"entityTypes":["customerId"]}]""")
            stubFuzzy("""{"matches":[{"id":"CUST-001","label":"Novák s.r.o.","score":0.95}]}""")
            stubLlmJoint(
                """{"functionId":"listInvoices","argsJson":"{}","confidence":0.92,"rationale":"clear match"}""",
            )

            val result = makeGraph().run(baseContext())

            val res = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            res.resolution.functionId shouldBe "listInvoices"
            res.resolution.confidence shouldBe 0.92
        }

        "low confidence triggers EmitAwaiting with a resume token" {
            stubLlmFilter("""[{"index":0,"entityTypes":["customerId"]}]""")
            stubFuzzy("""{"matches":[{"id":"CUST-X","label":"Novák","score":0.6}]}""")
            stubLlmJoint("""{"functionId":"listInvoices","argsJson":"{}","confidence":0.4,"rationale":"ambiguous"}""")

            val result = makeGraph().run(baseContext())

            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.state.parseState.resumeToken shouldNotBe null
            awaiting.awaiting.question shouldBe "Which interpretation did you mean?"
        }

        "HITL resume with valid token continues pipeline and resolves" {
            stubLlmFilter("""[{"index":0,"entityTypes":["customerId"]}]""")
            stubFuzzy("""{"matches":[{"id":"CUST-001","label":"Novák s.r.o.","score":0.95}]}""")
            stubLlmJoint("""{"functionId":"listInvoices","argsJson":"{}","confidence":0.88,"rationale":"clarified"}""")

            val config = testConfig()
            val tokenManager = HmacTokenManager(config)
            val nlp = nlpResult()
            val token =
                tokenManager.createResumeToken(
                    question = "faktury Novák",
                    parseHash = nlp.traceId,
                    domainCandidates = mapOf("0" to listOf("customerId")),
                    universalEntities = emptyList(),
                    ambiguityAsked = "Which customer?",
                    roundCounter = 1,
                    nlpLanguage = nlp.language,
                    nlpLanguageConfidence = nlp.languageConfidence,
                    nlpEngineUsed = nlp.engineUsed,
                    nlpTokens = Json.encodeToString(nlp.tokens),
                    nlpSentences = Json.encodeToString(nlp.sentences),
                    nlpParagraphs = Json.encodeToString(nlp.paragraphs),
                    nlpEntities = Json.encodeToString(nlp.entities),
                    nlpTraceId = nlp.traceId,
                    nlpElapsedMs = nlp.elapsedMs,
                    nlpMessages = Json.encodeToString(nlp.messages),
                )

            val ctx = baseContext().copy(parseState = baseContext().parseState.copy(resumeToken = token))
            val result = makeGraph(config).run(ctx)

            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
        }

        "invalid HITL resume token returns Error" {
            val ctx = baseContext().copy(parseState = baseContext().parseState.copy(resumeToken = "bad.token"))
            val result = makeGraph().run(ctx)

            val err = result.shouldBeInstanceOf<NodeResult.Error>()
            err.message shouldBe "Invalid resume token"
        }

        "malformed LLM filter response falls back to all spans then resolves" {
            stubLlmFilter("NOT_VALID_JSON")
            stubFuzzy("""{"matches":[{"id":"CUST-001","label":"Novák","score":0.9}]}""")
            stubLlmJoint("""{"functionId":"listInvoices","argsJson":"{}","confidence":0.85,"rationale":"fallback"}""")

            val result = makeGraph().run(baseContext())

            // parseFilterResponse falls back → all spans forwarded → joint inference resolves
            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
        }

        "fuzzy service unavailable degrades gracefully and joint inference still resolves" {
            stubLlmFilter("""[{"index":0,"entityTypes":["customerId"]}]""")
            fuzzyMock.stubFor(post(urlEqualTo("/match")).willReturn(serverError()))
            stubLlmJoint("""{"functionId":"listInvoices","argsJson":"{}","confidence":0.87,"rationale":"no binding"}""")

            val result = makeGraph().run(baseContext())

            // fuzzyMatches empty but joint inference still runs
            val res = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            res.resolution.functionId shouldBe "listInvoices"
        }
    }

    private fun stubLlmFilter(content: String) {
        llmMock.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("haiku")))
                .willReturn(okJson(llmResponseBody(content))),
        )
    }

    private fun stubLlmJoint(content: String) {
        llmMock.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("sonnet")))
                .willReturn(okJson(llmResponseBody(content))),
        )
    }

    private fun stubFuzzy(responseBody: String) {
        fuzzyMock.stubFor(
            post(urlEqualTo("/match"))
                .willReturn(okJson(responseBody)),
        )
    }

    private fun llmResponseBody(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}"""
    }

    private fun testConfig(
        llmPort: Int = llmMock.port(),
        fuzzyPort: Int = fuzzyMock.port(),
    ) = ResolverAppConfig(
        server = ServerConfig(port = 7171, host = "0.0.0.0"),
        nlp = EndpointConfig(host = "localhost", port = 8000, timeoutMs = 30_000),
        fuzzy = EndpointConfig(host = "localhost", port = fuzzyPort, timeoutMs = 15_000),
        llmGateway = EndpointConfig(host = "localhost", port = llmPort, timeoutMs = 60_000),
        hmac = HmacConfig(secretKey = "integ-test-key-32-bytes-padding!"),
        cache = CacheConfig(nlpLruSize = 100, resolutionLruSize = 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig(corpusPath = "eval/corpus/seed.jsonl"),
    )

    private fun makeGraph(config: ResolverAppConfig = testConfig()): ThemisGraphDeps {
        val tokenManager = HmacTokenManager(config)
        val llmClient = LlmGatewayClient(config.llmGateway.toLlmGatewayEndpoint())
        val fuzzyClient = FuzzyServiceClient(config.fuzzy)
        return ThemisGraphDeps(
            llm = llmClient,
            fuzzy = fuzzyClient,
            tokenManager = tokenManager,
            config = config,
            // baseContext is INVESTIGATION_DEEP, so routeToAgent is skipped and this
            // (intentionally unreachable) endpoint is never dialled.
            capabilities = CapabilitiesReadClient("http://localhost:1"),
            intentRules = IntentKindRules.load(),
        )
    }

    private fun nlpResult() =
        NlpAnalyzeResult(
            language = "cs",
            languageConfidence = 0.99,
            engineUsed = "stanza",
            tokens =
                listOf(
                    NlpToken(
                        text = "faktury",
                        charStart = 0,
                        charEnd = 7,
                        lemma = "faktura",
                        upos = "NOUN",
                        xpos = "NN",
                        feats = emptyMap(),
                        depHead = 0,
                        depRelation = "root",
                    ),
                    NlpToken(
                        text = "Novák",
                        charStart = 8,
                        charEnd = 13,
                        lemma = "Novák",
                        upos = "PROPN",
                        xpos = "NNP",
                        feats = emptyMap(),
                        depHead = 0,
                        depRelation = "nsubj",
                    ),
                ),
            sentences = listOf(NlpSpan(0, 13)),
            paragraphs = emptyList(),
            entities =
                listOf(
                    NlpEntity(
                        text = "Novák",
                        label = "PER",
                        charStart = 8,
                        charEnd = 13,
                        normalizedValue = "Novák",
                        sourceEngine = "stanza",
                    ),
                ),
            traceId = "trace-integ-1",
            elapsedMs = 10,
            messages = emptyList(),
        )

    private fun baseContext(): ResolverContext {
        val registry =
            Themis.Registry
                .newBuilder()
                .addEntityTypes(
                    Themis.EntityTypeSpec
                        .newBuilder()
                        .setEntityTypeRef("customerId")
                        .setDescription("Customer identifier")
                        .setFuzzyMatcherNamespace("customers"),
                ).addFunctionSpecs(
                    Themis.FunctionSpec
                        .newBuilder()
                        .setFunctionId("listInvoices")
                        .setDescription("List invoices for a customer")
                        .addParams(
                            Themis.ParamSpec
                                .newBuilder()
                                .setName("customerId")
                                .setType("string"),
                        ),
                ).build()

        return ResolverContext(
            requestId = "integ-test-1",
            conversationId = "integ-test-1",
            locale = "cs",
            // INVESTIGATION_DEEP preserves the pre-Phase-3 dispatch semantics here
            // (configured HITL max-rounds; routeToAgent skipped).
            profile = Themis.Profile.INVESTIGATION_DEEP,
            parseState = ParseState(nlpResponse = nlpResult()),
            registry = registry,
            recentEntities = emptyList(),
            recentTurns =
                listOf(
                    Themis.Turn
                        .newBuilder()
                        .setRole("user")
                        .setContent("faktury Novák")
                        .build(),
                ),
        )
    }
}

private suspend fun ThemisGraphDeps.run(state: ResolverContext): NodeResult = runThemisGraph(state, this)
