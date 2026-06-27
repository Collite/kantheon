package org.tatrman.kantheon.iris.api

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

class AuthSpec :
    StringSpec({

        val fixedNow = Instant.parse("2026-06-18T00:00:00Z")
        val auth = BearerAuthenticator(now = { fixedNow })

        "decodes sub + tenant from a valid bearer" {
            val caller = auth.authenticate(jwt("""{"sub":"u1","tenant":"acme"}"""))
            caller.shouldNotBeNull()
            caller.userId shouldBe "u1"
            caller.tenantId shouldBe "acme"
        }

        "missing or malformed bearer → null (401)" {
            auth.authenticate(null).shouldBeNull()
            auth.authenticate("Bearer").shouldBeNull()
            auth.authenticate("Bearer not-a-jwt").shouldBeNull()
            auth.authenticate(jwt("""{"no_sub":true}""")).shouldBeNull()
        }

        "an already-expired token fails closed (exp in the past)" {
            val expired = fixedNow.epochSecond - 60
            auth.authenticate(jwt("""{"sub":"u1","exp":$expired}""")).shouldBeNull()
        }

        "a still-valid exp is accepted" {
            val future = fixedNow.epochSecond + 3600
            auth.authenticate(jwt("""{"sub":"u1","exp":$future}""")).shouldNotBeNull()
        }
    })
