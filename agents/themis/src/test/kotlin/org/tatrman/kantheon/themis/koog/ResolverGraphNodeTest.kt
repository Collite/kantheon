package org.tatrman.kantheon.themis.koog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.client.NlpEntity
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
import org.tatrman.kantheon.themis.koog.nodes.extractUniversalStep
import org.tatrman.kantheon.themis.koog.nodes.filterRelevantSpansStep
import org.tatrman.kantheon.themis.koog.nodes.proposeDomainSpansStep
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.3 node-pipeline tests, re-pointed off the deleted `ThemisGraphDispatch`
 * (Phase 3 cleanup, 2026-06-21):
 *  - **full-pipeline** outcomes run through the production graph (`runThemisGraph`,
 *    via the [run] extension). The context is INVESTIGATION_DEEP so behaviour
 *    matches the old dispatch exactly — configured HITL max-rounds (3) and
 *    routeToAgent skipped.
 *  - **single-node** behaviour tests call the node step functions directly (the
 *    Koog graph has no `lastNode`-resume, so mid-pipeline state injection moved
 *    to direct step calls).
 */
class ResolverGraphNodeTest :
    StringSpec({

        // -------------------------------------------------------------------------
        // Full pipeline outcomes (runThemisGraph)
        // -------------------------------------------------------------------------

        "full pipeline -- high confidence -- emits resolution" {
            val result = deps(jointResponse = highConfJson("listInvoices")).run(minimalContext())
            val resolved = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolved.resolution.functionId shouldBe "listInvoices"
            resolved.resolution.confidence shouldBe 0.95
        }

        "full pipeline -- low confidence first round -- emits awaiting clarification" {
            val result = deps(jointResponse = lowConfJson("listInvoices")).run(minimalContext())
            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.awaiting.question shouldBe "Which interpretation did you mean?"
        }

        "full pipeline -- max rounds exhausted -- forces resolution at low confidence" {
            val result = deps(jointResponse = lowConfJson("listInvoices")).run(minimalContext().withRoundCounter(3))
            val resolved = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolved.resolution.functionId shouldBe "listInvoices"
        }

        // -------------------------------------------------------------------------
        // extractUniversal — NER label mapping (direct step)
        // -------------------------------------------------------------------------

        "extractUniversal -- PER maps to PERSON, LOC maps to LOCATION" {
            val out =
                extractUniversalStep(
                    nlpContext(entities = listOf(entity("John", "PER"), entity("Prague", "LOC"))),
                )
            val entities = out.parseState.universalEntities
            entities[0].entityType shouldBe Themis.UniversalEntityType.PERSON
            entities[0].rawText shouldBe "John"
            entities[1].entityType shouldBe Themis.UniversalEntityType.LOCATION
            entities[1].rawText shouldBe "Prague"
        }

        "extractUniversal -- DATE, MONEY, ORG labels with normalizedValue" {
            val out =
                extractUniversalStep(
                    nlpContext(
                        entities =
                            listOf(
                                entity("tomorrow", "DATE", normalizedValue = "2026-05-13"),
                                entity("100 Kč", "MONEY", normalizedValue = "100.0 CZK"),
                                entity("ACME", "ORG"),
                            ),
                    ),
                )
            val entities = out.parseState.universalEntities
            entities[0].entityType shouldBe Themis.UniversalEntityType.DATE
            entities[0].normalizedValue shouldBe "2026-05-13"
            entities[1].entityType shouldBe Themis.UniversalEntityType.MONEY
            entities[1].normalizedValue shouldBe "100.0 CZK"
            entities[2].entityType shouldBe Themis.UniversalEntityType.ORGANIZATION
        }

        "extractUniversal -- unknown label maps to MISC" {
            val out = extractUniversalStep(nlpContext(entities = listOf(entity("XYZ-42", "CUSTOM_TYPE"))))
            out.parseState.universalEntities[0].entityType shouldBe Themis.UniversalEntityType.MISC
        }

        // -------------------------------------------------------------------------
        // proposeDomainSpans — POS filtering (direct step)
        // -------------------------------------------------------------------------

        "proposeDomainSpans -- NOUN tokens become domain spans" {
            val out = proposeDomainSpansStep(nlpContext(tokens = listOf(nounTok("invoices", 0, 8))))
            val spans = out.parseState.domainSpans
            spans.size shouldBe 1
            spans[0].coveredText shouldBe "invoices"
            spans[0].pos shouldBe "NOUN"
        }

        "proposeDomainSpans -- PROPN tokens become domain spans" {
            val out = proposeDomainSpansStep(nlpContext(tokens = listOf(propnTok("Novák", 0, 5))))
            out.parseState.domainSpans.size shouldBe 1
            out.parseState.domainSpans[0].coveredText shouldBe "Novák"
        }

        "proposeDomainSpans -- VERB tokens are excluded" {
            val out = proposeDomainSpansStep(nlpContext(tokens = listOf(verbTok("show", 0, 4))))
            out.parseState.domainSpans.size shouldBe 0
        }

        "proposeDomainSpans -- mixed tokens extract only NOUN and PROPN" {
            val out =
                proposeDomainSpansStep(
                    nlpContext(
                        tokens = listOf(verbTok("show", 0, 4), nounTok("invoices", 5, 13), propnTok("Novák", 14, 19)),
                    ),
                )
            out.parseState.domainSpans.size shouldBe 2
        }

        // -------------------------------------------------------------------------
        // filterRelevantSpans (direct step)
        // -------------------------------------------------------------------------

        "filterRelevantSpans -- valid filter response selects span and annotates entityTypeCandidates" {
            val state = spansContext(listOf(domainSpan("invoices", 0, 8), domainSpan("Novak", 9, 14)))
            val out = filterRelevantSpansStep(state, llmReturning("""[{"index":1,"entityTypes":["customerId"]}]"""))
            out.parseState.filteredSpans.size shouldBe 1
            out.parseState.filteredSpans[0].coveredText shouldBe "Novak"
            out.parseState.filteredSpans[0].entityTypeCandidates shouldBe listOf("customerId")
        }

        "filterRelevantSpans -- invalid json falls back to all domain spans" {
            val state = spansContext(listOf(domainSpan("foo", 0, 3), domainSpan("bar", 4, 7)))
            val out = filterRelevantSpansStep(state, llmReturning("NOT_VALID_JSON"))
            out.parseState.filteredSpans.size shouldBe 2
        }

        "filterRelevantSpans -- unknown entityTypeRef is dropped" {
            val state = spansContext(listOf(domainSpan("test", 0, 4)))
            val out = filterRelevantSpansStep(state, llmReturning("""[{"index":0,"entityTypes":["unknownRef"]}]"""))
            out.parseState.filteredSpans.size shouldBe 0
        }

        // -------------------------------------------------------------------------
        // jointInference — response parsing (runThemisGraph)
        // -------------------------------------------------------------------------

        "joint inference -- json with code fences strips fences correctly" {
            val wrapped = "```json\n${highConfJson("listInvoices")}\n```"
            val result = deps(jointResponse = wrapped).run(minimalContext())
            val resolved = result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolved.resolution.functionId shouldBe "listInvoices"
            resolved.resolution.confidence shouldBe 0.95
        }

        "joint inference -- completely malformed json defaults to zero confidence" {
            val result = deps(jointResponse = "not json at all").run(minimalContext())
            result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
        }

        "joint inference -- partial json with only functionId uses zero confidence" {
            val result = deps(jointResponse = """{"functionId":"doSomething"}""").run(minimalContext())
            result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
        }

        "joint inference -- awaiting clarification options include function label" {
            val result = deps(jointResponse = lowConfJson("listInvoices")).run(minimalContext())
            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.awaiting.optionsList
                .map { it.label }
                .any { "listInvoices" in it } shouldBe true
        }

        // -------------------------------------------------------------------------
        // HITL resume flow (runThemisGraph)
        // -------------------------------------------------------------------------

        "hitl -- invalid token returns Error" {
            val result =
                deps(tokenPayload = null).run(minimalContext().withResumeToken("bad-token"))
            val error = result.shouldBeInstanceOf<NodeResult.Error>()
            error.message shouldBe "Invalid resume token"
        }

        "hitl -- valid token clears resumeToken and continues to resolution" {
            val result =
                deps(tokenPayload = tokenPayload(roundCounter = 1), jointResponse = highConfJson("listInvoices"))
                    .run(minimalContext().withResumeToken("valid-token"))
            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
        }

        "hitl -- resume token from EmitAwaiting state is non-empty" {
            val result = deps(jointResponse = lowConfJson("listInvoices")).run(minimalContext())
            val awaiting = result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.state.parseState.resumeToken shouldBe "mock-resume-token"
        }

        // -------------------------------------------------------------------------
        // Error handling (runThemisGraph)
        // -------------------------------------------------------------------------

        "llm throws exception -- graph catches and returns Error" {
            val result = deps(llmThrows = true).run(minimalContext())
            result.shouldBeInstanceOf<NodeResult.Error>()
        }
    })

// -------------------------------------------------------------------------
// Builders and helpers
// -------------------------------------------------------------------------

private suspend fun ThemisGraphDeps.run(state: ResolverContext): NodeResult = runThemisGraph(state, this)

private fun highConfJson(functionId: String) =
    """{"functionId":"$functionId","argsJson":"{}","confidence":0.95,"rationale":"Clear match"}"""

private fun lowConfJson(functionId: String) =
    """{"functionId":"$functionId","argsJson":"{}","confidence":0.4,"rationale":"Ambiguous"}"""

private fun tokenPayload(roundCounter: Int) =
    HmacTokenManager.TokenPayload(
        question = "test question",
        parseHash = "trace-123",
        domainCandidates = emptyMap(),
        universalEntities = emptyList(),
        ambiguityAsked = "",
        roundCounter = roundCounter,
        issuedAt = System.currentTimeMillis(),
    )

private fun deps(
    filterResponse: String = "NOT_VALID_JSON",
    jointResponse: String = lowConfJson(""),
    tokenPayload: HmacTokenManager.TokenPayload? = null,
    llmThrows: Boolean = false,
): ThemisGraphDeps {
    val tokenManager = mockk<HmacTokenManager>()
    val llmClient = mockk<LlmGatewayClient>()
    val fuzzyClient = mockk<FuzzyServiceClient>()
    // routeToAgent is skipped (INVESTIGATION_DEEP), so capabilities are never read.
    val capabilities = mockk<CapabilitiesReadClient>()

    if (llmThrows) {
        coEvery { llmClient.complete(any(), any(), any(), any(), any()) } throws RuntimeException("LLM crashed")
    } else {
        // First call = filterRelevantSpans (haiku); second = jointInference (sonnet).
        var llmCalls = 0
        val responses = listOf(filterResponse, jointResponse)
        coEvery { llmClient.complete(any(), any(), any(), any(), any()) } answers {
            Result.success(responses.getOrElse(llmCalls++) { responses.last() })
        }
    }

    coEvery { fuzzyClient.match(any(), any(), any(), any()) } returns emptyList()
    every { tokenManager.verifyAndDecode(any()) } returns tokenPayload
    every { tokenManager.nlpAnalyzeResultFromPayload(any()) } answers { minimalNlp() }
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
        config = testConfig(),
        capabilities = capabilities,
        intentRules = IntentKindRules.load(),
    )
}

private fun testConfig() =
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

private fun testRegistry(): Themis.Registry =
    Themis.Registry
        .newBuilder()
        .addEntityTypes(
            Themis.EntityTypeSpec
                .newBuilder()
                .setEntityTypeRef("customerId")
                .setDescription("Customer identifier")
                .setFuzzyMatcherNamespace("customer")
                .build(),
        ).addFunctionSpecs(
            Themis.FunctionSpec
                .newBuilder()
                .setFunctionId("listInvoices")
                .setDescription("List invoices for a customer")
                .build(),
        ).build()

private fun minimalNlp(
    tokens: List<NlpToken> = emptyList(),
    entities: List<NlpEntity> = emptyList(),
) = NlpAnalyzeResult(
    language = "cs",
    languageConfidence = 0.99,
    engineUsed = "spacy",
    tokens = tokens,
    sentences = emptyList(),
    paragraphs = emptyList(),
    entities = entities,
    traceId = "trace-123",
    elapsedMs = 100,
    messages = emptyList(),
)

/** Full-pipeline context; INVESTIGATION_DEEP keeps the old dispatch semantics. */
private fun minimalContext(
    tokens: List<NlpToken> = emptyList(),
    entities: List<NlpEntity> = emptyList(),
) = ResolverContext(
    requestId = "test-req",
    conversationId = "test-conv",
    registry = testRegistry(),
    locale = "cs",
    profile = Themis.Profile.INVESTIGATION_DEEP,
    parseState = ParseState(nlpResponse = minimalNlp(tokens, entities)),
)

/** Context carrying an NLP parse, for direct single-node step calls. */
private fun nlpContext(
    tokens: List<NlpToken> = emptyList(),
    entities: List<NlpEntity> = emptyList(),
) = ResolverContext(
    requestId = "test-req",
    conversationId = "test-conv",
    registry = testRegistry(),
    locale = "cs",
    parseState = ParseState(nlpResponse = minimalNlp(tokens, entities)),
)

/** Context with pre-proposed domain spans, for filterRelevantSpansStep. */
private fun spansContext(domainSpans: List<DomainSpan>) =
    ResolverContext(
        requestId = "test-req",
        conversationId = "test-conv",
        registry = testRegistry(),
        locale = "cs",
        parseState = ParseState(nlpResponse = minimalNlp(), domainSpans = domainSpans),
    )

private fun llmReturning(response: String): LlmGatewayClient =
    mockk {
        coEvery { complete(any(), any(), any(), any(), any()) } returns Result.success(response)
    }

private fun ResolverContext.withRoundCounter(n: Int) = copy(parseState = parseState.copy(roundCounter = n))

private fun ResolverContext.withResumeToken(token: String) = copy(parseState = parseState.copy(resumeToken = token))

private fun entity(
    text: String,
    label: String,
    normalizedValue: String = "",
    sourceEngine: String = "spacy",
) = NlpEntity(
    text = text,
    label = label,
    charStart = 0,
    charEnd = text.length,
    normalizedValue = normalizedValue,
    sourceEngine = sourceEngine,
)

private fun nounTok(
    text: String,
    start: Int,
    end: Int,
) = NlpToken(text, start, end, text.lowercase(), "NOUN", "_", emptyMap(), 0, "obj")

private fun propnTok(
    text: String,
    start: Int,
    end: Int,
) = NlpToken(text, start, end, text, "PROPN", "_", emptyMap(), 0, "nsubj")

private fun verbTok(
    text: String,
    start: Int,
    end: Int,
) = NlpToken(text, start, end, text.lowercase(), "VERB", "_", emptyMap(), 0, "ROOT")

private fun domainSpan(
    text: String,
    start: Int,
    end: Int,
) = DomainSpan(charStart = start, charEnd = end, coveredText = text, pos = "NOUN", depHead = 0, depRelation = "obj")
