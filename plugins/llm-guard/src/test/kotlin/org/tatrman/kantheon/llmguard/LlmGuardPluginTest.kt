package org.tatrman.kantheon.llmguard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.validator.spi.Door
import org.tatrman.validator.spi.PlanValidatorPlugin
import org.tatrman.validator.spi.PrincipalInfo
import org.tatrman.validator.spi.ValidationContext
import org.tatrman.validator.spi.Verdict
import java.util.ServiceLoader

/**
 * PL-P4.S2.T5 — the greenfield kantheon LLM-guard plugin on the open SPI. Proves: QUERY-only (PROGRAM
 * passes); approve→Pass / caveat→Advise / reject→Deny; the failure posture (unavailable → fail-closed Deny
 * by default, fail-open Advise); and ServiceLoader discoverability (so the platform's svarog host loads it —
 * the live query-door proof is at ⑥). The live gateway call is skeletoned (⑥); the tests drive a fake judge.
 */
class LlmGuardPluginTest :
    StringSpec({
        val principal = PrincipalInfo("ada", setOf("finance"))

        fun ctx(door: Door) = ValidationContext("SELECT 1".toByteArray(), principal, "sha256:w", door)

        fun guard(
            review: SemanticReview,
            posture: FailurePosture = FailurePosture.FAIL_CLOSED,
        ) = LlmGuardPlugin(judge = { _, _ -> review }, failurePosture = posture)

        "declares a stable id and spiVersion 1" {
            val g = guard(SemanticReview.Approve)
            g.id shouldBe "kantheon-llm-guard"
            g.spiVersion shouldBe PlanValidatorPlugin.SPI_VERSION
        }

        "QUERY-only: a PROGRAM context passes untouched (the judge is never consulted)" {
            LlmGuardPlugin(judge = { _, _ -> error("must not be called for PROGRAM") })
                .validate(ctx(Door.PROGRAM)) shouldBe Verdict.Pass
        }

        "QUERY approve → Pass" {
            guard(SemanticReview.Approve).validate(ctx(Door.QUERY)) shouldBe Verdict.Pass
        }

        "QUERY caveat → Advise (non-blocking, reason surfaced)" {
            val v = guard(SemanticReview.Caveat("aggregates PII — review")).validate(ctx(Door.QUERY))
            v.shouldBeInstanceOf<Verdict.Advise>()
            v.code shouldBe "llm_guard_caveat"
        }

        "QUERY reject → Deny" {
            val v = guard(SemanticReview.Reject("exfiltration risk")).validate(ctx(Door.QUERY))
            v.shouldBeInstanceOf<Verdict.Deny>()
            v.code shouldBe "llm_guard_rejected"
        }

        "gateway unavailable → fail-closed Deny by default; fail-open → Advise" {
            guard(SemanticReview.Unavailable).validate(ctx(Door.QUERY)).shouldBeInstanceOf<Verdict.Deny>()
            guard(
                SemanticReview.Unavailable,
                FailurePosture.FAIL_OPEN,
            ).validate(ctx(Door.QUERY)).shouldBeInstanceOf<Verdict.Advise>()
        }

        "the skeleton gateway judge is Unavailable with no gateway configured (⑥ wires the live call)" {
            GatewaySemanticJudge.fromEnv().review("x".toByteArray(), principal) shouldBe SemanticReview.Unavailable
        }

        "ServiceLoader-discoverable (META-INF/services + no-arg ctor) — a host like svarog loads it" {
            ServiceLoader.load(PlanValidatorPlugin::class.java).map { it.id } shouldContain "kantheon-llm-guard"
        }
    })
