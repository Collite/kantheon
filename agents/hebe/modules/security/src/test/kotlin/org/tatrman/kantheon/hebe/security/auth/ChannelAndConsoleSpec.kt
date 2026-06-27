package org.tatrman.kantheon.hebe.security.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.hebe.config.ConfigValidationException
import org.tatrman.kantheon.hebe.config.ConsoleAuth as ConsoleAuthAxis
import org.tatrman.kantheon.hebe.config.PlatformIdentity

/**
 * Channel-identity enforcement (T5) + console-auth selection/verification (T3).
 */
class ChannelAndConsoleSpec : StringSpec({

    // ── chat_user_map (T5) ───────────────────────────────────────────────────

    "keycloak profile rejects an unmapped chat_id, maps a known one" {
        val guard = ChannelIdentityGuard(PlatformIdentity.KEYCLOAK, mapOf("111" to "bora"))
        guard.isAllowed("111") shouldBe true
        guard.resolveUser("111") shouldBe "bora"
        guard.isAllowed("999") shouldBe false
    }

    "local profile does not enforce channel identity" {
        val guard = ChannelIdentityGuard(PlatformIdentity.NONE, emptyMap())
        guard.isAllowed("anything") shouldBe true
        guard.resolveUser("anything") shouldBe ChannelIdentityGuard.UNENFORCED
    }

    "boot fails fast if chat_user_map omits the bound user on a keycloak profile" {
        shouldThrow<ConfigValidationException> {
            ChannelIdentityGuard.validateAtBoot(PlatformIdentity.KEYCLOAK, mapOf("111" to "someone-else"), "bora")
        }.message!!.contains("bora") shouldBe true
    }

    "boot passes when the map includes the bound user" {
        ChannelIdentityGuard.validateAtBoot(PlatformIdentity.KEYCLOAK, mapOf("111" to "bora"), "bora")
    }

    "boot is a no-op on a local profile" {
        ChannelIdentityGuard.validateAtBoot(PlatformIdentity.NONE, emptyMap(), "bora")
    }

    // ── console auth (T3) ────────────────────────────────────────────────────

    "console-auth mode resolves from the axis" {
        ConsoleAuthMode.from(ConsoleAuthAxis.PASSWORD) shouldBe ConsoleAuthMode.PASSWORD
        ConsoleAuthMode.from(ConsoleAuthAxis.OIDC) shouldBe ConsoleAuthMode.OIDC
    }

    "OIDC verifier authenticates a valid token and extracts the subject" {
        val v =
            OidcSessionVerifier(
                expectedIssuer = "https://kc/realms/kantheon",
                expectedAudience = "hebe",
                validateSignature = { true },
                now = { 1000 },
            )
        val r =
            v.verify(
                "tok",
                mapOf("iss" to "https://kc/realms/kantheon", "aud" to "hebe", "exp" to 2000, "preferred_username" to "bora"),
            )
        r.shouldBeInstanceOf<ConsoleAuthResult.Authenticated>()
        (r as ConsoleAuthResult.Authenticated).subject shouldBe "bora"
    }

    "OIDC verifier rejects issuer mismatch, expiry, and bad signature" {
        val v = OidcSessionVerifier("https://kc/realms/kantheon", "hebe", validateSignature = { true }, now = { 1000 })
        v.verify("t", mapOf("iss" to "https://evil", "exp" to 2000, "sub" to "x"))
            .shouldBeInstanceOf<ConsoleAuthResult.Rejected>()
        v.verify("t", mapOf("iss" to "https://kc/realms/kantheon", "aud" to "hebe", "exp" to 500, "sub" to "x"))
            .shouldBeInstanceOf<ConsoleAuthResult.Rejected>()
        OidcSessionVerifier("https://kc/realms/kantheon", "hebe", validateSignature = { false })
            .verify("t", mapOf("iss" to "https://kc/realms/kantheon", "sub" to "x"))
            .shouldBeInstanceOf<ConsoleAuthResult.Rejected>()
    }
})
