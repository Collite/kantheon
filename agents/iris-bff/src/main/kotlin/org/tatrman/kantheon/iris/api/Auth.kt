package org.tatrman.kantheon.iris.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

/** The authenticated caller derived from the bearer token (contracts §2). */
data class CallerIdentity(
    val userId: String,
    val tenantId: String,
    val bearer: String,
)

/**
 * Keycloak bearer extraction. Two modes, by `iris.auth.verify-signature`:
 *
 * - **off (default at local/dev):** the JWT payload is decoded for the `sub` and
 *   tenant claims and `exp` is enforced, but the signature is not checked.
 * - **on (production):** a [JwtSignatureVerifier] (JWKS-backed) validates the
 *   RS256 signature + `iss`/`aud`/`exp` *before* claims are trusted. If signature
 *   verification is enabled but no verifier was wired, the authenticator
 *   **fails closed** (every bearer → null → 401) rather than silently skipping it.
 *
 * A missing or malformed bearer always yields `null` → 401. The user bearer is
 * retained for downstream OBO forwarding (Themis / agents, Phase 3).
 */
class BearerAuthenticator(
    private val tenantClaim: String = "tenant",
    private val defaultTenant: String = "default",
    private val verifySignature: Boolean = false,
    private val signatureVerifier: JwtSignatureVerifier? = null,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(BearerAuthenticator::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(authorizationHeader: String?): CallerIdentity? {
        val header = authorizationHeader?.trim() ?: return null
        val parts = header.split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val token = parts[1]
        val segments = token.split(".")
        if (segments.size < 2) return null

        val claims = resolveClaims(token, segments) ?: return null
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull ?: return null
        val tenant = claims[tenantClaim]?.jsonPrimitive?.contentOrNull ?: defaultTenant
        return CallerIdentity(userId = sub, tenantId = tenant, bearer = token)
    }

    /** Verified claims (signature mode) or decoded-and-exp-checked claims (decode mode). */
    private fun resolveClaims(
        token: String,
        segments: List<String>,
    ): JsonObject? {
        if (verifySignature) {
            val verifier =
                signatureVerifier ?: run {
                    // Fail closed: configured to verify but nothing to verify with.
                    log.error("iris auth: verify-signature = true but no JWKS verifier wired — rejecting all bearers")
                    return null
                }
            return verifier.verify(token, now().epochSecond)
        }
        val payload =
            runCatching { String(Base64.getUrlDecoder().decode(padBase64(segments[1]))) }.getOrNull() ?: return null
        val claims = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        // Fail closed on an already-expired token (`exp`, epoch seconds). This is
        // the edge check; mid-run expiry on long dispatches is handled by the
        // downstream 401 → fresh-bearer resume (kantheon-security; Phase 3).
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull
        if (exp != null && exp <= now().epochSecond) return null
        return claims
    }

    private fun padBase64(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
}

/** Resolve the caller or respond 401 and return null. */
suspend fun ApplicationCall.requireCaller(auth: BearerAuthenticator): CallerIdentity? {
    val caller = auth.authenticate(request.headers[HttpHeaders.Authorization])
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorBody("unauthorized", "Missing or invalid bearer token"))
    }
    return caller
}
