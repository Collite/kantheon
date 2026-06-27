package org.tatrman.kantheon.iris.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Best-effort role extraction from a Keycloak bearer (PD-8 visibility filtering +
 * admin gates). Roles travel on the forwarded bearer (identity stays bearer-only
 * at the edge — kantheon-security §3); this reads `realm_access.roles` and every
 * `resource_access.<client>.roles` from the (already-authenticated) JWT payload.
 * The signature is validated by [BearerAuthenticator] when verification is on;
 * this is a claims read, not a second trust decision.
 */
object BearerRoles {
    private val json = Json { ignoreUnknownKeys = true }

    fun rolesOf(bearer: String): Set<String> {
        val token = bearer.removePrefix("Bearer ").trim()
        val segments = token.split(".")
        if (segments.size < 2) return emptySet()
        val payload =
            runCatching { String(Base64.getUrlDecoder().decode(pad(segments[1]))) }.getOrNull() ?: return emptySet()
        val claims = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return emptySet()

        val roles = mutableSetOf<String>()
        (claims["realm_access"] as? JsonObject)?.let { roles += rolesArray(it) }
        (claims["resource_access"] as? JsonObject)?.values?.forEach { client ->
            (client as? JsonObject)?.let { roles += rolesArray(it) }
        }
        // Some tokens carry a flat `roles` claim too.
        (claims["roles"] as? JsonArray)?.let { roles += it.mapNotNull { e -> str(e) } }
        return roles
    }

    fun hasRole(
        bearer: String,
        role: String,
    ): Boolean = role in rolesOf(bearer)

    private fun rolesArray(o: JsonObject): Set<String> =
        (o["roles"] as? JsonArray)?.mapNotNull { str(it) }?.toSet() ?: emptySet()

    private fun str(e: kotlinx.serialization.json.JsonElement): String? =
        runCatching { e.jsonPrimitive.content }.getOrNull()

    private fun pad(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
}
