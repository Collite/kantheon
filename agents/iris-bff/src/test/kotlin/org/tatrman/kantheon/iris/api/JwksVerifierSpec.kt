package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.util.Base64

/** RS256 JWKS verification (Stage 1.4 A3) — verifier + BearerAuthenticator signature mode. */
class JwksVerifierSpec :
    StringSpec({

        val rsa: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Instant.parse("2026-06-18T00:00:00Z")
        val future = now.epochSecond + 3600

        fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        // Mint a JWT signed by `key` with the given header kid + alg.
        fun jwt(
            claimsJson: String,
            kid: String = "k1",
            alg: String = "RS256",
            key: PrivateKey = rsa.private,
        ): String {
            val header = b64url("""{"alg":"$alg","typ":"JWT","kid":"$kid"}""".toByteArray())
            val payload = b64url(claimsJson.toByteArray())
            val signingInput = "$header.$payload"
            val sig =
                Signature.getInstance("SHA256withRSA").run {
                    initSign(key)
                    update(signingInput.toByteArray())
                    sign()
                }
            return "$signingInput.${b64url(sig)}"
        }

        // Provider that only knows kid "k1" → the RSA public key.
        val provider = JwksProvider { kid -> if (kid == "k1") rsa.public else null }

        "a valid RS256 token verifies and returns its claims" {
            val verifier = RsaJwksSignatureVerifier(provider)
            val claims = verifier.verify(jwt("""{"sub":"u1","tenant":"acme","exp":$future}"""), now.epochSecond)
            claims.shouldNotBeNull()
            claims["sub"]?.jsonPrimitive?.contentOrNull shouldBe "u1"
        }

        "an unknown kid is rejected" {
            val verifier = RsaJwksSignatureVerifier(provider)
            verifier.verify(jwt("""{"sub":"u1","exp":$future}""", kid = "rotated"), now.epochSecond).shouldBeNull()
        }

        "a signature from a different key is rejected" {
            val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val verifier = RsaJwksSignatureVerifier(provider)
            verifier.verify(jwt("""{"sub":"u1","exp":$future}""", key = other.private), now.epochSecond).shouldBeNull()
        }

        "a non-RS256 alg is rejected" {
            val verifier = RsaJwksSignatureVerifier(provider)
            verifier.verify(jwt("""{"sub":"u1","exp":$future}""", alg = "none"), now.epochSecond).shouldBeNull()
        }

        "an expired token is rejected" {
            val verifier = RsaJwksSignatureVerifier(provider)
            val past = now.epochSecond - 60
            verifier.verify(jwt("""{"sub":"u1","exp":$past}"""), now.epochSecond).shouldBeNull()
        }

        "issuer and audience are enforced when configured" {
            val verifier = RsaJwksSignatureVerifier(provider, issuer = "https://kc/realms/k", audience = "iris")
            // matching iss + aud (aud as a single string)
            verifier
                .verify(jwt("""{"sub":"u1","exp":$future,"iss":"https://kc/realms/k","aud":"iris"}"""), now.epochSecond)
                .shouldNotBeNull()
            // matching iss + aud as an array containing the expected audience
            verifier
                .verify(
                    jwt("""{"sub":"u1","exp":$future,"iss":"https://kc/realms/k","aud":["x","iris"]}"""),
                    now.epochSecond,
                ).shouldNotBeNull()
            // wrong issuer
            verifier
                .verify(jwt("""{"sub":"u1","exp":$future,"iss":"https://evil","aud":"iris"}"""), now.epochSecond)
                .shouldBeNull()
            // wrong audience
            verifier
                .verify(
                    jwt("""{"sub":"u1","exp":$future,"iss":"https://kc/realms/k","aud":"other"}"""),
                    now.epochSecond,
                ).shouldBeNull()
        }

        "a provider that cannot supply the key fails closed" {
            val empty = JwksProvider { null }
            RsaJwksSignatureVerifier(
                empty,
            ).verify(jwt("""{"sub":"u1","exp":$future}"""), now.epochSecond).shouldBeNull()
        }

        "BearerAuthenticator in signature mode accepts a verified token" {
            val auth =
                BearerAuthenticator(
                    verifySignature = true,
                    signatureVerifier = RsaJwksSignatureVerifier(provider),
                    now = { now },
                )
            val caller = auth.authenticate("Bearer ${jwt("""{"sub":"u1","tenant":"acme","exp":$future}""")}")
            caller.shouldNotBeNull()
            caller.userId shouldBe "u1"
            caller.tenantId shouldBe "acme"
        }

        "BearerAuthenticator in signature mode rejects a bad token" {
            val auth =
                BearerAuthenticator(
                    verifySignature = true,
                    signatureVerifier = RsaJwksSignatureVerifier(provider),
                    now = { now },
                )
            auth.authenticate("Bearer ${jwt("""{"sub":"u1","exp":${now.epochSecond - 60}}""")}").shouldBeNull()
        }

        "BearerAuthenticator fails closed when verify is on but no verifier is wired" {
            val auth = BearerAuthenticator(verifySignature = true, signatureVerifier = null, now = { now })
            auth.authenticate("Bearer ${jwt("""{"sub":"u1","exp":$future}""")}").shouldBeNull()
        }
    })
