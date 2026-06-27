package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.themis.config.CacheConfig
import org.tatrman.kantheon.themis.config.EndpointConfig
import org.tatrman.kantheon.themis.config.EvalConfig
import org.tatrman.kantheon.themis.config.HitlConfig
import org.tatrman.kantheon.themis.config.HmacConfig
import org.tatrman.kantheon.themis.config.ResolverAppConfig
import org.tatrman.kantheon.themis.config.ServerConfig
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Phase 3 Stage 3.4 — per-profile behaviour knobs (the end-to-end traversal is
 * covered by the component specs; here the pure decision functions).
 */
class ProfileBehaviourSpec :
    StringSpec({

        "CHAT_QUICK uses 3 fuzzy candidates; INVESTIGATION_DEEP uses 10" {
            fuzzyLimitFor(Themis.Profile.CHAT_QUICK) shouldBe 3
            fuzzyLimitFor(Themis.Profile.INVESTIGATION_DEEP) shouldBe 10
            // PROFILE_UNSPECIFIED behaves as CHAT_QUICK.
            fuzzyLimitFor(Themis.Profile.PROFILE_UNSPECIFIED) shouldBe 3
        }

        "CHAT_QUICK caps HITL at 1 round; INVESTIGATION_DEEP uses the configured max" {
            val config = configWithMaxRounds(3)
            maxHitlRoundsFor(Themis.Profile.CHAT_QUICK, config) shouldBe 1
            maxHitlRoundsFor(Themis.Profile.INVESTIGATION_DEEP, config) shouldBe 3
        }
    })

private fun configWithMaxRounds(max: Int) =
    ResolverAppConfig(
        server = ServerConfig(7171, "0.0.0.0"),
        nlp = EndpointConfig("localhost", 8000, 30_000),
        fuzzy = EndpointConfig("localhost", 8001, 15_000),
        llmGateway = EndpointConfig("localhost", 8002, 60_000),
        hmac = HmacConfig(secretKey = "integ-test-key-32-bytes-padding!"),
        cache = CacheConfig(100, 100),
        hitl = HitlConfig(confidenceThreshold = 0.75, maxRounds = max),
        eval = EvalConfig("eval/corpus/seed.jsonl"),
    )
