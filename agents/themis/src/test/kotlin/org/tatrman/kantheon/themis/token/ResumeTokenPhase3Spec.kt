package org.tatrman.kantheon.themis.token

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig

/**
 * Phase 3 Stage 3.4 (T6) — the resume token grows two Phase-3 fields additively;
 * the kotlinx defaults guarantee pre-Phase-3 tokens still decode.
 */
class ResumeTokenPhase3Spec :
    StringSpec({

        val tm = HmacTokenManager(config())

        "Phase 3 fields round-trip through the token" {
            val token =
                tm.createResumeToken(
                    question = "q",
                    parseHash = "h",
                    domainCandidates = emptyMap(),
                    universalEntities = emptyList(),
                    ambiguityAsked = "which?",
                    roundCounter = 1,
                    profileAtIssue = "INVESTIGATION_DEEP",
                    alternatesOffered = listOf("pythia", "golem-erp"),
                )
            val payload = tm.verifyAndDecode(token)
            payload.shouldNotBeNull()
            payload.profileAtIssue shouldBe "INVESTIGATION_DEEP"
            payload.alternatesOffered shouldContainExactly listOf("pythia", "golem-erp")
        }

        "a token issued without the Phase 3 fields decodes with defaults" {
            val token =
                tm.createResumeToken(
                    question = "q",
                    parseHash = "h",
                    domainCandidates = emptyMap(),
                    universalEntities = emptyList(),
                    ambiguityAsked = "which?",
                    roundCounter = 1,
                )
            val payload = tm.verifyAndDecode(token)
            payload.shouldNotBeNull()
            payload.profileAtIssue shouldBe "CHAT_QUICK"
            payload.alternatesOffered shouldBe emptyList()
        }
    })

private fun config() =
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
