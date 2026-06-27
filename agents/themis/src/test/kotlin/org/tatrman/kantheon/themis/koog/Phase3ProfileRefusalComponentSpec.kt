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
import org.tatrman.kantheon.common.v1.AgentId
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
 * Phase 3 Stage 3.4 — STRICT mode refuses end-to-end through the production Koog
 * graph; INTERACTIVE (same low-confidence input) keeps the clarification loop.
 * routing_hint = golem-erp pins Layer 0 so the only blocker is the ambiguity.
 */
class Phase3ProfileRefusalComponentSpec :
    StringSpec({

        "STRICT + low-confidence intent → RefusalWithGaps (AMBIGUOUS_INTENT)" {
            val result = runBlocking { runThemisGraph(ambiguousState(Themis.HitlProfile.STRICT), deps()) }
            result.shouldBeInstanceOf<NodeResult.EmitRefusal>()
            result.refusal.gapsList
                .first()
                .kind shouldBe Themis.GapKind.AMBIGUOUS_INTENT
        }

        "INTERACTIVE + same input → AwaitingClarification (not refusal)" {
            val result = runBlocking { runThemisGraph(ambiguousState(Themis.HitlProfile.INTERACTIVE), deps()) }
            result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
        }
    })

private fun deps(): ThemisGraphDeps {
    val config = cfg()
    val llm =
        mockk<LlmGatewayClient> {
            coEvery { complete(any(), any(), "haiku", any(), any()) } returns Result.success("[]")
            coEvery { complete(any(), any(), "sonnet", any(), any()) } returns
                Result.success(
                    """{"functionId":"listInvoices","argsJson":"{}","confidence":0.3,"rationale":"unsure"}""",
                )
        }
    val capabilities =
        mockk<CapabilitiesReadClient> {
            coEvery { listAgents() } returns
                jsonObj(
                    """{"agents":[{"agentId":"golem-erp","agentKind":"AREA_QA","intentKindsSupported":["PROCEDURAL"],"areaEntities":["invoice"],"nonRoutable":false}],"messages":[]}""",
                )
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

private fun ambiguousState(hitl: Themis.HitlProfile): ResolverContext =
    ResolverContext(
        conversationId = "refusal-1",
        registry = Themis.Registry.getDefaultInstance(),
        locale = "cs",
        profile = Themis.Profile.CHAT_QUICK,
        routingHint = AgentId.newBuilder().setValue("golem-erp").build(), // Layer 0 → no capability blocker
        hitl = hitl,
        parseState =
            ParseState(
                nlpResponse =
                    NlpAnalyzeResult(
                        language = "cs",
                        languageConfidence = 0.99,
                        engineUsed = "stanza",
                        tokens =
                            listOf(
                                NlpToken("Jak", 0, 3, "jak", "ADV", "", emptyMap(), 2, "advmod"),
                                NlpToken("prodej", 4, 10, "prodej", "NOUN", "", emptyMap(), 0, "root"),
                            ),
                        sentences = listOf(NlpSpan(0, 10)),
                        paragraphs = emptyList(),
                        entities = emptyList(),
                        traceId = "t-refusal",
                        elapsedMs = 5,
                        messages = emptyList(),
                    ),
            ),
    )

private fun jsonObj(literal: String): JsonObject = Json.parseToJsonElement(literal) as JsonObject

private fun cfg() =
    ResolverAppConfig(
        server = ServerConfig(7171, "0.0.0.0"),
        nlp = EndpointConfig("localhost", 8000, 30_000),
        fuzzy = EndpointConfig("localhost", 8001, 15_000),
        llmGateway = EndpointConfig("localhost", 8002, 60_000),
        hmac = HmacConfig(secretKey = "integ-test-key-32-bytes-padding!"),
        cache = CacheConfig(100, 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig("eval/corpus/seed.jsonl"),
    )
