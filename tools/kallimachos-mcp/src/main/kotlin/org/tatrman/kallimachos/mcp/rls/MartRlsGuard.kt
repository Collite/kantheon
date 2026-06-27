package org.tatrman.kallimachos.mcp.rls

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kallimachos.mcp.ForwardSpec
import org.tatrman.kallimachos.mcp.LibraryForwarder

sealed interface RlsDecision {
    data object Allow : RlsDecision

    data class Deny(
        val reason: String,
    ) : RlsDecision
}

/**
 * Enforces the mart RLS predicate at the MCP edge BEFORE the store is touched
 * (architecture §9): for a mart-read tool it resolves the caller identity from
 * the OBO bearer, fetches the notebook ACL, and evaluates [MartRls]. Write ops
 * (`createNotebook`/`addToNotebook`) are ops-gated (admin role). A denial
 * increments `kallimachos_mart_rls_denied_total`. The store filters by the
 * scoped mart as defence-in-depth.
 */
class MartRlsGuard(
    private val forwarder: LibraryForwarder,
    private val meters: MeterRegistry? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(
        toolName: String,
        args: JsonObject,
        bearer: String?,
    ): RlsDecision {
        val identity = IdentityResolver.fromBearer(bearer)
        return when (MartRls.scopeOf(toolName)) {
            MartScope.MART_READ -> checkMartRead(args, identity)
            MartScope.ADMIN_WRITE ->
                if (MartRls.ADMIN_ROLE in identity.roles) RlsDecision.Allow else deny("write ops are ops/admin-gated")
            // List returns only the caller's own visible marts — the store scopes by principal.
            MartScope.CALLER_SCOPED -> RlsDecision.Allow
            // Fail closed: an unmapped tool is never silently forwarded unscoped.
            null -> deny("tool '$toolName' is not permitted at the RLS edge")
        }
    }

    private suspend fun checkMartRead(
        args: JsonObject,
        identity: Identity,
    ): RlsDecision {
        val notebookId = args["notebookId"]?.jsonPrimitive?.content ?: return deny("notebook_id required")
        if (notebookId == MartRls.ADMIN_SCOPE) {
            return if (MartRls.canReadAdminScope(
                    identity,
                )
            ) {
                RlsDecision.Allow
            } else {
                deny("admin scope requires ${MartRls.ADMIN_ROLE}")
            }
        }
        val acl = fetchAcl(notebookId) ?: return deny("notebook '$notebookId' not visible")
        return if (MartRls.canRead(identity, acl)) RlsDecision.Allow else deny("no access to mart '$notebookId'")
    }

    private suspend fun fetchAcl(notebookId: String): NotebookAcl? {
        val result =
            forwarder.forward(
                ForwardSpec(io.ktor.http.HttpMethod.Get, "/notebooks/$notebookId"),
                bearer = null,
            )
        if (result.status >= 400) return null
        return try {
            val o = json.parseToJsonElement(result.body).jsonObject
            NotebookAcl(
                id = o["id"]?.jsonPrimitive?.content ?: notebookId,
                ownerUserId = o["ownerUserId"]?.jsonPrimitive?.content ?: "",
                visibilityRoles = (o["visibilityRoles"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun deny(reason: String): RlsDecision.Deny {
        meters?.counter("kallimachos_mart_rls_denied_total")?.increment()
        return RlsDecision.Deny(reason)
    }
}
