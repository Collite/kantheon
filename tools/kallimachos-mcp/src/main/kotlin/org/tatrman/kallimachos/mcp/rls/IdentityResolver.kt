package org.tatrman.kallimachos.mcp.rls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Resolves the caller [Identity] from the forwarded OBO bearer (Argos `bearer`
 * role source). v1 reads the JWT payload's `sub` + roles (`realm_access.roles`
 * and a flat `roles` claim) WITHOUT signature verification.
 *
 * **Trust boundary.** This mirrors the theseus-mcp resolver: signature validation
 * is terminated at the authenticated edge (ingress / BFF) that mints the OBO
 * token *before* it reaches this wrapper. kallimachos-mcp MUST NOT be exposed to
 * callers that can supply an unminted bearer — a NetworkPolicy that admits only
 * the constellation edge is the operational guarantee. A non-JWT/blank bearer
 * yields an anonymous identity (no roles, blank sub) — which [MartRls.canRead]
 * denies for any non-public mart (and a blank owner never matches a blank sub).
 */
object IdentityResolver {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromBearer(bearer: String?): Identity {
        val token =
            bearer
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim()
                .orEmpty()
        if (token.isBlank()) return Identity("", emptySet())
        val payload = decodePayload(token) ?: return Identity("", emptySet())
        val sub = payload["sub"]?.jsonPrimitive?.content ?: ""
        val roles = rolesOf(payload)
        return Identity(sub, roles)
    }

    private fun decodePayload(token: String): JsonObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        return try {
            // Base64url-pad to a multiple of 4 — a JWT segment whose length is not
            // a multiple of 4 would otherwise fail to decode (matches theseus-mcp).
            val padded = parts[1].padEnd(((parts[1].length + 3) / 4) * 4, '=')
            val decoded = String(Base64.getUrlDecoder().decode(padded))
            json.parseToJsonElement(decoded).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun rolesOf(payload: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        (payload["roles"] as? JsonArray)?.let { out += it.toStrings() }
        (payload["realm_access"] as? JsonObject)?.get("roles")?.let {
            (it as? JsonArray)?.let { a ->
                out +=
                    a.toStrings()
            }
        }
        return out
    }

    private fun JsonArray.toStrings(): List<String> = jsonArray.mapNotNull { it.jsonPrimitive.content }
}
