package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.tatrman.kantheon.themis.client.NlpAnalyzeResult
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.koog.InferenceResult
import org.tatrman.kantheon.themis.koog.NodeResult
import org.tatrman.kantheon.themis.koog.ParseState
import org.tatrman.kantheon.themis.koog.ResolverContext
import org.tatrman.kantheon.themis.koog.toNodeResult
import org.tatrman.kantheon.themis.token.HmacTokenManager
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.3 T5 spec — focused unit coverage of the four newly extracted step
 * functions (`parseJointInferenceResponse`, `decideHitlOrEmitStep`,
 * `decodeResumeTokenStep`, `buildAwaitingClarification`). The carry-over
 * `ResolverGraphNodeTest` already exercises these end-to-end through the
 * dispatch loop; this spec gives fast, descriptive failure messages.
 */
class InferenceAndHitlNodesSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // parseJointInferenceResponse — Resolver-style JSON parse with stub-on-fail
        // -------------------------------------------------------------------------

        "parseJointInferenceResponse — happy path: extracts functionId, confidence, rationale" {
            val response =
                """
                {"functionId":"listInvoices","argsJson":"{\"customerId\":\"CUST-1\"}","confidence":0.95,"rationale":"high"}
                """.trimIndent()
            val out = parseJointInferenceResponse(response)
            out.functionId shouldBe "listInvoices"
            out.argsJson shouldContain "customerId"
            out.confidence shouldBe 0.95
            out.rationale shouldBe "high"
        }

        "parseJointInferenceResponse — strips ```json fences" {
            val response = """```json
{"functionId":"f","argsJson":"{}","confidence":0.5,"rationale":""}
```"""
            parseJointInferenceResponse(response).functionId shouldBe "f"
        }

        "parseJointInferenceResponse — malformed JSON returns stub with confidence=0 and parse-error rationale" {
            val out = parseJointInferenceResponse("not valid json at all")
            out.functionId shouldBe ""
            out.confidence shouldBe 0.0
            out.rationale shouldContain "Parse error"
        }

        // -------------------------------------------------------------------------
        // decideHitlOrEmitStep — confidence threshold + maxRounds fallthrough
        // -------------------------------------------------------------------------

        "decideHitlOrEmitStep — confidence above threshold emits Resolution" {
            val state = contextWith(inferenceResult = inference(confidence = 0.9))
            val out = decideHitlOrEmitStep(state, mockTokenManager(), testConfig()).toNodeResult()
            val resolved = out.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolved.resolution.functionId shouldBe "listInvoices"
            resolved.resolution.confidence shouldBeGreaterThanOrEqual 0.9
        }

        "decideHitlOrEmitStep — confidence below threshold + rounds < max emits Awaiting" {
            val state = contextWith(inferenceResult = inference(confidence = 0.3), roundCounter = 0)
            val out = decideHitlOrEmitStep(state, mockTokenManager(), testConfig()).toNodeResult()
            val awaiting = out.shouldBeInstanceOf<NodeResult.EmitAwaiting>()
            awaiting.awaiting.question shouldBe "Which interpretation did you mean?"
        }

        "decideHitlOrEmitStep — maxRounds reached forces Resolution even at low confidence" {
            val state = contextWith(inferenceResult = inference(confidence = 0.3), roundCounter = 3)
            val out = decideHitlOrEmitStep(state, mockTokenManager(), testConfig()).toNodeResult()
            val resolved = out.shouldBeInstanceOf<NodeResult.EmitResolution>()
            resolved.resolution.confidence shouldBe 0.3
        }

        "decideHitlOrEmitStep — no inferenceResult → Error" {
            val state = contextWith(inferenceResult = null)
            val out = decideHitlOrEmitStep(state, mockTokenManager(), testConfig()).toNodeResult()
            val err = out.shouldBeInstanceOf<NodeResult.Error>()
            err.message shouldContain "No inference"
        }

        // -------------------------------------------------------------------------
        // decodeResumeTokenStep — HMAC verification flow
        // -------------------------------------------------------------------------

        "decodeResumeTokenStep — null token → Error" {
            val state = contextWith(resumeToken = null)
            val out = decodeResumeTokenStep(state, mockTokenManager()).toNodeResult()
            out.shouldBeInstanceOf<NodeResult.Error>().message shouldContain "No resume token"
        }

        "decodeResumeTokenStep — verifyAndDecode returns null → Error" {
            val state = contextWith(resumeToken = "garbage")
            val tm = mockk<HmacTokenManager>()
            every { tm.verifyAndDecode("garbage") } returns null
            decodeResumeTokenStep(state, tm).toNodeResult().shouldBeInstanceOf<NodeResult.Error>().message shouldContain
                "Invalid resume token"
        }

        "decodeResumeTokenStep — happy path: roundCounter transplanted, resumeToken cleared, Continue" {
            val state = contextWith(resumeToken = "valid")
            val tm = mockk<HmacTokenManager>()
            every { tm.verifyAndDecode("valid") } returns
                HmacTokenManager.TokenPayload(
                    question = "q",
                    parseHash = "",
                    domainCandidates = emptyMap(),
                    universalEntities = emptyList(),
                    ambiguityAsked = "",
                    roundCounter = 2,
                    issuedAt = 0,
                )
            val out = decodeResumeTokenStep(state, tm).toNodeResult()
            val cont = out.shouldBeInstanceOf<NodeResult.Continue>()
            cont.state.parseState.roundCounter shouldBe 2
            cont.state.parseState.resumeToken shouldBe null
            cont.state.parseState.lastNode shouldBe "decodeTokenAndApplyChoice"
        }

        // -------------------------------------------------------------------------
        // buildAwaitingClarification — proto construction shape
        // -------------------------------------------------------------------------

        "buildAwaitingClarification — appends an opt_other option after the alternatives" {
            val state = contextWith(inferenceResult = inference("f", 0.5))
            val awaiting = buildAwaitingClarification(state, inference("f", 0.5))
            // 1 default alternative (from result) + opt_other.
            awaiting.optionsList shouldHaveSize 2
            awaiting.optionsList[0].optionId shouldBe "opt_1"
            awaiting.optionsList[1].optionId shouldBe "opt_other"
            awaiting.optionsList[1].label shouldBe "Something else"
        }

        "buildAwaitingClarification — caps to top 3 alternatives + opt_other" {
            val result =
                InferenceResult(
                    functionId = "f0",
                    argsJson = "{}",
                    bindings = emptyList(),
                    confidence = 0.5,
                    alternatives =
                        (1..5).map { i ->
                            Themis.InferenceAlternative
                                .newBuilder()
                                .setFunctionId("f$i")
                                .setConfidence(0.5)
                                .setRationale("alt $i")
                                .build()
                        },
                    rationale = "",
                )
            val awaiting = buildAwaitingClarification(contextWith(), result)
            awaiting.optionsList shouldHaveSize 4 // 3 alternatives + opt_other
            awaiting.optionsList.last().optionId shouldBe "opt_other"
        }
    })

// -- Test fixtures ------------------------------------------------------------

private fun testConfig(): ResolverAppConfig =
    ResolverAppConfig(
        server = ServerConfig(port = 0, host = "0.0.0.0"),
        nlp = EndpointConfig(host = "localhost", port = 0, timeoutMs = 0),
        fuzzy = EndpointConfig(host = "localhost", port = 0, timeoutMs = 0),
        llmGateway = EndpointConfig(host = "localhost", port = 0, timeoutMs = 0),
        hmac = HmacConfig(secretKey = "test-key-for-unit-tests-only-32b"),
        cache = CacheConfig(nlpLruSize = 0, resolutionLruSize = 0),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = 3),
        eval = EvalConfig(corpusPath = ""),
    )

private fun mockTokenManager(): HmacTokenManager =
    mockk<HmacTokenManager>().also {
        every {
            it.createResumeToken(
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
        } returns "mock-token"
    }

private fun inference(
    functionId: String = "listInvoices",
    confidence: Double = 0.95,
): InferenceResult =
    InferenceResult(
        functionId = functionId,
        argsJson = "{}",
        bindings = emptyList(),
        confidence = confidence,
        alternatives = emptyList(),
        rationale = "test rationale",
    )

private fun contextWith(
    inferenceResult: InferenceResult? = null,
    roundCounter: Int = 0,
    resumeToken: String? = null,
): ResolverContext =
    ResolverContext(
        conversationId = "test-conv",
        parseState =
            ParseState(
                nlpResponse =
                    NlpAnalyzeResult(
                        language = "cs",
                        languageConfidence = 1.0,
                        engineUsed = "test",
                        tokens = emptyList(),
                        sentences = emptyList(),
                        paragraphs = emptyList(),
                        entities = emptyList(),
                        traceId = "",
                        elapsedMs = 0,
                        messages = emptyList(),
                    ),
                inferenceResult = inferenceResult,
                roundCounter = roundCounter,
                resumeToken = resumeToken,
            ),
    )
