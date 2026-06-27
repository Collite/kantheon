package org.tatrman.kantheon.themis.koog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.client.NlpSpan
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.koog.nodes.IntentKindRules
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Phase 3 Stage 3.3 — component test: a CHAT_QUICK Czech ERP question routes to
 * `golem-erp` via Layer 1 through the production Koog graph (`runThemisGraph`),
 * with the registry + LLM + fuzzy clients mocked (planning-conventions.md §4).
 */
class Phase3RoutingComponentSpec :
    StringSpec({

        "CHAT_QUICK: a Czech ERP question routes to golem-erp via Layer 1" {
            val llm = mockk<LlmGatewayClient>()
            // filterRelevantSpans (haiku): faktury→invoice, Shell→customer.
            coEvery { llm.complete(any(), any(), "haiku", any(), any()) } returns
                Result.success("""[{"index":0,"entityTypes":["invoice"]},{"index":1,"entityTypes":["customer"]}]""")
            // jointInference (sonnet): a confident resolution.
            coEvery { llm.complete(any(), any(), "sonnet", any(), any()) } returns
                Result.success(
                    """{"functionId":"listInvoices","argsJson":"{}","confidence":0.9,"rationale":"clear"}""",
                )

            val state = contextWith(erpQuestionParse(), erpRegistry())
            val result = runBlocking { runThemisGraph(state, deps(llm)) }

            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            result.resolution.intentKind shouldBe Themis.IntentKind.PROCEDURAL
            result.resolution.routing.chosenAgentId.value shouldBe "golem-erp"
            result.resolution.routing.layerHit shouldBe 1
        }

        "routingEnabled=false (reduced MCP surface) leaves Resolution.routing unset" {
            val llm = mockk<LlmGatewayClient>()
            coEvery { llm.complete(any(), any(), "haiku", any(), any()) } returns
                Result.success("""[{"index":0,"entityTypes":["invoice"]},{"index":1,"entityTypes":["customer"]}]""")
            coEvery { llm.complete(any(), any(), "sonnet", any(), any()) } returns
                Result.success(
                    """{"functionId":"listInvoices","argsJson":"{}","confidence":0.9,"rationale":"clear"}""",
                )

            val state = contextWith(erpQuestionParse(), erpRegistry()).copy(routingEnabled = false)
            val result = runBlocking { runThemisGraph(state, deps(llm)) }

            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            result.resolution.hasRouting() shouldBe false
            result.resolution.intentKind shouldBe Themis.IntentKind.PROCEDURAL
        }
    })

private fun deps(llm: LlmGatewayClient): ThemisGraphDeps {
    val config = routingConfig()
    val capabilities =
        mockk<CapabilitiesReadClient> {
            coEvery { listAgents() } returns jsonObj(AGENTS)
            coEvery { list() } returns jsonObj("""{"entries":[],"messages":[]}""")
        }
    return ThemisGraphDeps(
        llm = llm,
        fuzzy = mockk<FuzzyServiceClient>(relaxed = true),
        tokenManager = HmacTokenManager(config),
        config = config,
        capabilities = capabilities,
        intentRules = IntentKindRules.load(),
    )
}

private fun contextWith(
    parse: NlpAnalyzeResult,
    registry: Themis.Registry,
): ResolverContext =
    ResolverContext(
        requestId = "route-comp-1",
        conversationId = "route-comp-1",
        registry = registry,
        locale = "cs",
        profile = Themis.Profile.CHAT_QUICK,
        parseState = ParseState(nlpResponse = parse),
    )

/** "Které faktury Shell neuhradil?" — single clause; faktury(NOUN)+Shell(PROPN) are domain spans. */
private fun erpQuestionParse() =
    NlpAnalyzeResult(
        language = "cs",
        languageConfidence = 0.99,
        engineUsed = "stanza",
        tokens =
            listOf(
                udToken("Které", "DET", "det", 2, "který"),
                udToken("faktury", "NOUN", "obj", 4, "faktura"),
                udToken("Shell", "PROPN", "nsubj", 4, "shell"),
                udToken("neuhradil", "VERB", "root", 0, "neuhradit"),
            ),
        sentences = listOf(NlpSpan(0, 30)),
        paragraphs = emptyList(),
        entities = emptyList(),
        traceId = "trace-route",
        elapsedMs = 5,
        messages = emptyList(),
    )

private fun erpRegistry(): Themis.Registry =
    Themis.Registry
        .newBuilder()
        .addEntityTypes(
            Themis.EntityTypeSpec
                .newBuilder()
                .setEntityTypeRef("invoice")
                .setFuzzyMatcherNamespace("invoices"),
        ).addEntityTypes(
            Themis.EntityTypeSpec
                .newBuilder()
                .setEntityTypeRef("customer")
                .setFuzzyMatcherNamespace("customers"),
        ).addFunctionSpecs(
            Themis.FunctionSpec
                .newBuilder()
                .setFunctionId("listInvoices")
                .setDescription("List invoices"),
        ).build()

private fun routingConfig() =
    ResolverAppConfig(
        server = ServerConfig(port = 7171, host = "0.0.0.0"),
        nlp = EndpointConfig("localhost", 8000, 30_000),
        fuzzy = EndpointConfig("localhost", 8001, 15_000),
        llmGateway = EndpointConfig("localhost", 8002, 60_000),
        hmac = HmacConfig(secretKey = "integ-test-key-32-bytes-padding!"),
        cache = CacheConfig(nlpLruSize = 100, resolutionLruSize = 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig(corpusPath = "eval/corpus/seed.jsonl"),
    )

private var cursor = 0

private fun udToken(
    text: String,
    upos: String,
    dep: String,
    head: Int,
    lemma: String = text.lowercase(),
): NlpToken {
    val start = cursor
    cursor += text.length + 1
    return NlpToken(text, start, start + text.length, lemma, upos, "", emptyMap(), head, dep)
}

private fun jsonObj(literal: String): JsonObject = Json.parseToJsonElement(literal) as JsonObject

private val AGENTS =
    """
    {"agents":[
      {"agentKind":"INVESTIGATOR","agentId":"pythia","intentKindsSupported":["RCA","FORECAST","SIMULATION","PROCEDURAL"],
       "descriptionForRouter":"investigator","capabilityRefs":["model.fit.arima:v1"],"areaEntities":[],"nonRoutable":false},
      {"agentKind":"AREA_QA","agentId":"golem-erp","intentKindsSupported":["PROCEDURAL"],
       "descriptionForRouter":"ERP Q&A","areaName":"ERP","areaEntities":["customer","invoice"],"nonRoutable":false},
      {"agentKind":"AREA_QA","agentId":"golem-hr","intentKindsSupported":["PROCEDURAL"],
       "descriptionForRouter":"HR Q&A","areaName":"HR","areaEntities":["employee"],"nonRoutable":false}
    ],"messages":[]}
    """.trimIndent()
