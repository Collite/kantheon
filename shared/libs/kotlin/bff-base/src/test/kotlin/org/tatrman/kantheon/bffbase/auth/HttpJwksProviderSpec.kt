package org.tatrman.kantheon.bffbase.auth

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** HTTP JWKS fetch + cache against a Wiremock'd Keycloak `/certs` (EXAMPLES §9). */
class HttpJwksProviderSpec :
    StringSpec({

        fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        // Strip the sign byte BigInteger may prepend, mirroring how Keycloak emits n/e.
        fun unsigned(b: ByteArray) = if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b

        fun jwksJson(
            kid: String,
            key: RSAPublicKey,
        ): String {
            val n = b64url(unsigned(key.modulus.toByteArray()))
            val e = b64url(unsigned(BigInteger.valueOf(key.publicExponent.toLong()).toByteArray()))
            return """{"keys":[{"kty":"RSA","kid":"$kid","alg":"RS256","use":"sig","n":"$n","e":"$e"}]}"""
        }

        "fetches the JWKS and resolves a key by kid" {
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(
                    get(urlPathEqualTo("/certs")).willReturn(
                        aResponse()
                            .withHeader(
                                "Content-Type",
                                "application/json",
                            ).withBody(jwksJson("k1", rsa.public as RSAPublicKey)),
                    ),
                )
                val provider = HttpJwksProvider("${wm.baseUrl()}/certs")
                provider.key("k1").shouldNotBeNull()
                // A kid the JWKS does not carry stays null (fail-closed at the verifier).
                provider.key("absent").shouldBeNull()
            } finally {
                wm.stop()
            }
        }

        "a 500 from the JWKS endpoint leaves the provider empty (fail-closed)" {
            val wm = WireMockServer(options().dynamicPort())
            wm.start()
            try {
                wm.stubFor(get(urlPathEqualTo("/certs")).willReturn(aResponse().withStatus(500)))
                HttpJwksProvider("${wm.baseUrl()}/certs").key("k1").shouldBeNull()
            } finally {
                wm.stop()
            }
        }
    })
