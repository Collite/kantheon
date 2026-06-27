package org.tatrman.kantheon.bffbase.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Base64

private fun jwt(payloadJson: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
    return "Bearer h.$payload.s"
}

/** Decode-mode bearer extraction (signature verification off). */
class BearerAuthenticatorSpec :
    StringSpec({

        val fixedNow = Instant.parse("2026-06-23T00:00:00Z")
        val auth = BearerAuthenticator(now = { fixedNow })

        "decodes sub + tenant from a valid bearer" {
            val caller = auth.authenticate(jwt("""{"sub":"u1","tenant":"acme"}"""))
            caller.shouldNotBeNull()
            caller.userId shouldBe "u1"
            caller.tenantId shouldBe "acme"
        }

        "falls back to the default tenant when the claim is absent" {
            val auth2 = BearerAuthenticator(defaultTenant = "default", now = { fixedNow })
            auth2.authenticate(jwt("""{"sub":"u1"}"""))?.tenantId shouldBe "default"
        }

        "honours a custom tenant claim name" {
            val auth2 = BearerAuthenticator(tenantClaim = "tid", now = { fixedNow })
            auth2.authenticate(jwt("""{"sub":"u1","tid":"acme"}"""))?.tenantId shouldBe "acme"
        }

        "missing or malformed bearer → null (401)" {
            auth.authenticate(null).shouldBeNull()
            auth.authenticate("Bearer").shouldBeNull()
            auth.authenticate("Bearer not-a-jwt").shouldBeNull()
            auth.authenticate(jwt("""{"no_sub":true}""")).shouldBeNull()
        }

        "an already-expired token fails closed (exp in the past)" {
            auth.authenticate(jwt("""{"sub":"u1","exp":${fixedNow.epochSecond - 60}}""")).shouldBeNull()
        }

        "a still-valid exp is accepted" {
            auth.authenticate(jwt("""{"sub":"u1","exp":${fixedNow.epochSecond + 3600}}""")).shouldNotBeNull()
        }
    })
