package org.tatrman.kantheon.bffbase.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

/**
 * Supplies RSA public keys by JWT `kid` for signature verification. Injectable
 * so tests run against an in-memory key set with no network.
 */
fun interface JwksProvider {
    fun key(kid: String): PublicKey?
}

/**
 * Verifies a JWT's signature and standard claims; returns the verified claims
 * object, or null if the token is not trustworthy (fail-closed).
 */
fun interface JwtSignatureVerifier {
    fun verify(
        token: String,
        nowEpochSeconds: Long,
    ): JsonObject?
}

internal fun base64UrlDecode(s: String): ByteArray {
    val padded =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
    return Base64.getUrlDecoder().decode(padded)
}

/**
 * RS256 JWT verifier backed by a [JwksProvider]. Validates, in order: the header
 * `alg` is `RS256`; a `kid` is present and resolves to a key; the RSA signature
 * over `header.payload` is valid; `exp` is in the future; `iss`/`aud` match when
 * configured. Any failure (incl. a provider that can't supply the key) returns
 * null — the bearer is rejected, never accepted on doubt.
 *
 * Keycloak signs with RS256 by default; other algs are out of scope at v1.
 */
class RsaJwksSignatureVerifier(
    private val provider: JwksProvider,
    private val issuer: String? = null,
    private val audience: String? = null,
) : JwtSignatureVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun verify(
        token: String,
        nowEpochSeconds: Long,
    ): JsonObject? =
        runCatching {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val header = json.parseToJsonElement(String(base64UrlDecode(parts[0]))).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != "RS256") return null
            val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: return null
            val key = provider.key(kid) ?: return null

            val signingInput = "${parts[0]}.${parts[1]}".toByteArray()
            val sig = base64UrlDecode(parts[2])
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(signingInput)
            if (!verifier.verify(sig)) return null

            val claims = json.parseToJsonElement(String(base64UrlDecode(parts[1]))).jsonObject
            val exp = claims["exp"]?.jsonPrimitive?.longOrNull
            if (exp != null && exp <= nowEpochSeconds) return null
            if (issuer != null && claims["iss"]?.jsonPrimitive?.contentOrNull != issuer) return null
            if (audience != null && !audienceMatches(claims["aud"], audience)) return null
            claims
        }.getOrNull()

    /** `aud` is a string or an array of strings (RFC 7519); match either shape. */
    private fun audienceMatches(
        aud: JsonElement?,
        expected: String,
    ): Boolean =
        when (aud) {
            is JsonPrimitive -> aud.contentOrNull == expected
            is JsonArray -> aud.any { it.jsonPrimitive.contentOrNull == expected }
            else -> false
        }
}

/**
 * HTTP-backed [JwksProvider] with a `kid`-keyed cache. On a cache miss the whole
 * JWKS is (re)fetched — this is also the key-rotation path: a new `kid` triggers
 * a refresh. A fetch/parse failure leaves the cache untouched and returns null
 * (fail-closed at the verifier). The live JWKS URI is supplied at wiring time
 * (issuer → `jwks_uri`); construction does no I/O.
 *
 * `key()` blocks on the first fetch per `kid`; acceptable behind the cache for v1.
 */
class HttpJwksProvider(
    private val jwksUri: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : JwksProvider {
    private val log = LoggerFactory.getLogger(HttpJwksProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: Map<String, PublicKey> = emptyMap()

    override fun key(kid: String): PublicKey? {
        cache[kid]?.let { return it }
        return refresh()[kid]
    }

    @Synchronized
    private fun refresh(): Map<String, PublicKey> {
        val fetched =
            runCatching {
                val req =
                    HttpRequest
                        .newBuilder(URI.create(jwksUri))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) error("JWKS fetch ${resp.statusCode()}")
                parseJwks(resp.body())
            }.getOrElse {
                log.warn("bff-base auth: JWKS refresh from {} failed: {}", jwksUri, it.message)
                return cache
            }
        cache = fetched
        return fetched
    }

    private fun parseJwks(body: String): Map<String, PublicKey> {
        val keys = json.parseToJsonElement(body).jsonObject["keys"]?.jsonArray ?: return emptyMap()
        return keys
            .mapNotNull { it.jsonObject }
            .filter { it["kty"]?.jsonPrimitive?.contentOrNull == "RSA" }
            .mapNotNull { jwk ->
                val kid = jwk["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val n = jwk["n"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val e = jwk["e"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                runCatching { kid to rsaKey(n, e) }.getOrNull()
            }.toMap()
    }

    private fun rsaKey(
        nB64: String,
        eB64: String,
    ): PublicKey {
        val modulus = BigInteger(1, base64UrlDecode(nB64))
        val exponent = BigInteger(1, base64UrlDecode(eB64))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }
}
