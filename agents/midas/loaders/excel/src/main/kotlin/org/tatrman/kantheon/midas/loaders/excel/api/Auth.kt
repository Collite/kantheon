package org.tatrman.kantheon.midas.loaders.excel.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.tatrman.kantheon.midas.loaders.excel.client.CallContext
import java.time.Instant
import java.util.Base64

/** The authenticated caller; `bearer` is forwarded on-behalf-of to Midas-core. */
data class CallerIdentity(
    val userId: String,
    val tenantId: String,
    val bearer: String,
) {
    fun callContext(): CallContext = CallContext(bearer = bearer, tenantId = tenantId, userId = userId)
}

/**
 * Validate-only bearer authentication — the kantheon v1 posture shared with
 * Midas-core / iris-bff. Decodes `sub` / `tenant` / `exp` from the JWT payload.
 *
 * ⚠ The signature is **not** verified (a forged token grants its chosen tenant); the
 * loader forwards the same bearer to Midas-core, so isolation is enforced there by
 * RLS — but it is only as strong as the (currently unverified) token. JWKS
 * verification is the same platform-wide follow-up tracked in Midas-core's `Auth`.
 */
class BearerAuthenticator(
    private val tenantClaim: String = "tenant",
    private val defaultTenant: String = "default",
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(authorizationHeader: String?): CallerIdentity? {
        val header = authorizationHeader?.trim() ?: return null
        val parts = header.split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val segments = parts[1].split(".")
        if (segments.size < 2) return null

        val payload =
            runCatching { String(Base64.getUrlDecoder().decode(padBase64(segments[1]))) }.getOrNull() ?: return null
        val claims = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull ?: return null
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull
        if (exp != null && exp <= now().epochSecond) return null
        val tenant = claims[tenantClaim]?.jsonPrimitive?.contentOrNull ?: defaultTenant
        return CallerIdentity(userId = sub, tenantId = tenant, bearer = parts[1])
    }

    private fun padBase64(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
}
