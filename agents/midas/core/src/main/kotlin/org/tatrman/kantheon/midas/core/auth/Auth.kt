package org.tatrman.kantheon.midas.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64

/**
 * The authenticated caller. `tenantId` comes from the JWT `tenant` claim;
 * `roles` from `realm_access.roles` (the kantheon realm). `bearer` is the raw
 * token for any onward OBO call.
 *
 * **Authorization (v1, explicit decision).** `roles` is captured but Midas-core
 * applies **no role-based authorization** — any caller with a valid bearer is
 * authorized to read+write within their tenant; the tenant boundary (RLS) is the
 * only access control. RBAC over `roles` is a deliberate follow-up, not an
 * accidental omission. Until then the strength of isolation equals the strength of
 * the bearer — see [BearerAuthenticator] on signature verification.
 */
data class CallerIdentity(
    val userId: String,
    val tenantId: String,
    val roles: Set<String>,
    val bearer: String,
)

/**
 * Validate-only bearer authentication — the kantheon v1 posture shared with
 * iris-bff and golem. The token is decoded for `sub` / `tenant` / `realm_access.
 * roles`, and rejected if absent/malformed or past `exp`.
 *
 * ⚠ **The JWT signature is NOT verified yet.** This decodes the payload only, so a
 * forged token grants its chosen `tenant`/`roles`. JWKS signature verification is
 * the realm-issuer-driven hardening follow-up and is **not implemented** — it is a
 * real code change (fetch JWKS, verify the RS256 signature, validate `iss`/`aud`),
 * not a flag flip. To prevent a false sense of security, constructing this with
 * `verifySignature = true` fails fast rather than silently accepting unverified
 * tokens. This posture must close before Midas serves any non-fixture tenant data.
 */
class BearerAuthenticator(
    private val tenantClaim: String = "tenant",
    private val defaultTenant: String = "default",
    private val verifySignature: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    init {
        check(!verifySignature) {
            "JWKS signature verification is not yet implemented; do not enable verifySignature " +
                "until the JWKS/issuer verification path is wired (see Auth.kt)."
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(authorizationHeader: String?): CallerIdentity? {
        val header = authorizationHeader?.trim() ?: return null
        val parts = header.split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val segments = parts[1].split(".")
        if (segments.size < 2) return null

        val payload =
            runCatching {
                String(Base64.getUrlDecoder().decode(padBase64(segments[1])))
            }.getOrNull() ?: return null
        val claims = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull ?: return null
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull
        if (exp != null && exp <= now().epochSecond) return null
        val tenant = claims[tenantClaim]?.jsonPrimitive?.contentOrNull ?: defaultTenant
        val roles =
            runCatching {
                claims["realm_access"]
                    ?.jsonObject
                    ?.get("roles")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.toSet()
            }.getOrNull() ?: emptySet()
        return CallerIdentity(userId = sub, tenantId = tenant, roles = roles, bearer = parts[1])
    }

    private fun padBase64(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
}
