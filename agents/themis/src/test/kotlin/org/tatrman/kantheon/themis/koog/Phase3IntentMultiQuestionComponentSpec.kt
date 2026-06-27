package org.tatrman.kantheon.themis.koog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.llm.client.LlmGatewayClient
import org.tatrman.kantheon.themis.client.FuzzyServiceClient
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.client.NlpEntity
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
 * Phase 3 Stage 3.2 — component test of the two new nodes wired into the
 * production Koog graph (`runThemisGraph`). Mocked LLM/fuzzy clients only
 * (planning-conventions.md §4: the integration suite is separate).
 */
class Phase3IntentMultiQuestionComponentSpec :
    StringSpec({

        "CHAT_QUICK resolve populates Resolution.intent_kind for a Czech RCA question" {
            val llm = mockk<LlmGatewayClient>()
            // filterRelevantSpans (haiku) → no relevant spans; jointInference (sonnet) → resolution.
            coEvery { llm.complete(any(), any(), "haiku", any(), any()) } returns Result.success("[]")
            coEvery { llm.complete(any(), any(), "sonnet", any(), any()) } returns
                Result.success(
                    """{"functionId":"revenueReport","argsJson":"{}","confidence":0.9,"rationale":"clear"}""",
                )

            val state = contextWith(rcaSingleQuestionParse())
            val result = runBlocking { runThemisGraph(state, deps(llm)) }

            result.shouldBeInstanceOf<NodeResult.EmitResolution>()
            result.resolution.intentKind shouldBe Themis.IntentKind.RCA
        }

        "compound Czech question short-circuits to AwaitingClarification.MultiQuestion" {
            val llm = mockk<LlmGatewayClient>() // must never be called — detectMultiQuestion short-circuits.

            val state = contextWith(compoundQuestionParse())
            val result = runBlocking { runThemisGraph(state, deps(llm)) }

            result.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            result.awaiting.kindCase shouldBe Themis.AwaitingClarification.KindCase.MULTI_QUESTION
            result.awaiting.multiQuestion.subQuestionsList shouldHaveSize 2
            result.awaiting.multiQuestion.decomposition shouldBe Themis.Decomposition.SPLIT
        }
    })

private fun deps(llm: LlmGatewayClient): ThemisGraphDeps {
    val config = testConfig()
    val capabilities =
        mockk<CapabilitiesReadClient> {
            coEvery { listAgents() } returns jsonObj("""{"agents":[],"messages":[]}""")
            coEvery { list() } returns jsonObj("""{"entries":[],"messages":[]}""")
        }
    return ThemisGraphDeps(
        llm = llm,
        fuzzy = mockk<FuzzyServiceClient>(),
        tokenManager = HmacTokenManager(config),
        config = config,
        capabilities = capabilities,
        intentRules = IntentKindRules.load(),
    )
}

private fun jsonObj(literal: String): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.Json
        .parseToJsonElement(literal) as kotlinx.serialization.json.JsonObject

private fun testConfig() =
    ResolverAppConfig(
        server = ServerConfig(port = 7171, host = "0.0.0.0"),
        nlp = EndpointConfig(host = "localhost", port = 8000, timeoutMs = 30_000),
        fuzzy = EndpointConfig(host = "localhost", port = 8001, timeoutMs = 15_000),
        llmGateway = EndpointConfig(host = "localhost", port = 8002, timeoutMs = 60_000),
        hmac = HmacConfig(secretKey = "integ-test-key-32-bytes-padding!"),
        cache = CacheConfig(nlpLruSize = 100, resolutionLruSize = 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig(corpusPath = "eval/corpus/seed.jsonl"),
    )

private fun contextWith(parse: NlpAnalyzeResult): ResolverContext =
    ResolverContext(
        requestId = "comp-1",
        conversationId = "comp-1",
        registry = Themis.Registry.getDefaultInstance(),
        locale = "cs",
        parseState = ParseState(nlpResponse = parse),
    )

/** "Proč klesly tržby Castrolu?" — one clause head, 'proč' fires the RCA rule. */
private fun rcaSingleQuestionParse() =
    NlpAnalyzeResult(
        language = "cs",
        languageConfidence = 0.99,
        engineUsed = "stanza",
        tokens =
            listOf(
                udToken("Proč", "ADV", "advmod", head = 2, lemma = "proč"),
                udToken("klesly", "VERB", "root", head = 0, lemma = "klesnout"),
                udToken("tržby", "NOUN", "nsubj", head = 2, lemma = "tržba"),
                udToken("Castrolu", "PROPN", "nmod", head = 3, lemma = "castrol"),
            ),
        sentences = listOf(NlpSpan(0, 26)),
        paragraphs = emptyList(),
        entities = emptyList(),
        traceId = "trace-comp-rca",
        elapsedMs = 5,
        messages = emptyList(),
    )

/** "Které faktury Shell neuhradil a jaká byla Q3 marže?" — two disjoint clauses. */
private fun compoundQuestionParse() =
    NlpAnalyzeResult(
        language = "cs",
        languageConfidence = 0.99,
        engineUsed = "stanza",
        tokens =
            listOf(
                udToken("Které", "DET", "det", head = 2),
                udToken("faktury", "NOUN", "obj", head = 4),
                udToken("Shell", "PROPN", "nsubj", head = 4),
                udToken("neuhradil", "VERB", "root", head = 0),
                udToken("a", "CCONJ", "cc", head = 7),
                udToken("jaká", "DET", "det", head = 9),
                udToken("byla", "VERB", "conj", head = 4),
                udToken("Q3", "PROPN", "compound", head = 9),
                udToken("marže", "NOUN", "nsubj", head = 7),
            ),
        sentences = listOf(NlpSpan(0, 51)),
        paragraphs = emptyList(),
        entities =
            listOf(
                NlpEntity("Shell", "ORG", 14, 19, "Shell", "stanza"),
            ),
        traceId = "trace-comp-multi",
        elapsedMs = 5,
        messages = emptyList(),
    )

private var cursor = 0

private fun udToken(
    text: String,
    upos: String,
    dep: String,
    head: Int,
    lemma: String = text.lowercase(),
): NlpToken {
    // Stable increasing offsets within a single parse build call.
    val start = cursor
    cursor += text.length + 1
    return NlpToken(
        text = text,
        charStart = start,
        charEnd = start + text.length,
        lemma = lemma,
        upos = upos,
        xpos = "",
        feats = emptyMap(),
        depHead = head,
        depRelation = dep,
    )
}
