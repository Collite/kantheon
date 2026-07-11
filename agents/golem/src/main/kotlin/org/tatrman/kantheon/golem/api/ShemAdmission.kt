package org.tatrman.kantheon.golem.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.golem.shem.ShemContext
import java.time.Instant
import java.util.Base64

private val log = LoggerFactory.getLogger(ShemAdmission::class.java)

/** The authenticated caller admitted to a Golem turn. */
data class AdmittedCaller(
    val userId: String,
    val tenantId: String,
    val roles: Set<String>,
    val bearer: String,
)

/** Outcome of an admission check — admit the caller or deny with a status + Rule-6 message. */
sealed interface AdmissionResult {
    data class Admitted(
        val caller: AdmittedCaller,
    ) : AdmissionResult

    data class Denied(
        val status: HttpStatusCode,
        val code: String,
        val message: String,
    ) : AdmissionResult
}

/**
 * PD-8 request admission (kantheon-security §3.3): every `/v1` endpoint validates
 * the inbound bearer and re-checks the pod's `visibility_roles` against the caller's
 * realm roles. This guards the Themis-bypass path (direct callers) — Themis already
 * filters its routing view by roles, but agents must not trust that.
 *
 * **Bearer handling mirrors iris-bff/query-mcp: decode-only at v1.** The JWT
 * payload is decoded for `sub`/`preferred_username`, `exp` (fail-closed), tenant,
 * and `realm_access.roles`; JWKS signature verification terminates at the edge.
 */
class ShemAdmission(
    private val shem: ShemContext,
    private val tenantClaim: String = "tenant",
    private val defaultTenant: String = "default",
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun admit(authorizationHeader: String?): AdmissionResult {
        val caller =
            authenticate(authorizationHeader)
                ?: return AdmissionResult.Denied(
                    HttpStatusCode.Unauthorized,
                    "unauthorized",
                    "Missing or invalid bearer token",
                )
        if (!shem.isVisibleTo(caller.roles)) {
            return AdmissionResult.Denied(
                HttpStatusCode.Forbidden,
                "forbidden",
                "Caller is not entitled to Shem '${shem.golemId}'",
            )
        }
        return AdmissionResult.Admitted(caller)
    }

    fun authenticate(authorizationHeader: String?): AdmittedCaller? {
        val header = authorizationHeader?.trim() ?: return null
        val parts = header.split(" ").filter { it.isNotEmpty() }
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        val segments = parts[1].split(".")
        if (segments.size < 2) return null

        val payload = runCatching { String(Base64.getUrlDecoder().decode(pad(segments[1]))) }.getOrNull() ?: return null
        val claims = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val sub =
            claims["preferred_username"]?.jsonPrimitive?.contentOrNull
                ?: claims["sub"]?.jsonPrimitive?.contentOrNull
                ?: return null
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull
        if (exp != null && exp <= now().epochSecond) return null
        val tenant = claims[tenantClaim]?.jsonPrimitive?.contentOrNull ?: defaultTenant
        return AdmittedCaller(userId = sub, tenantId = tenant, roles = realmRoles(claims), bearer = parts[1])
    }

    private fun realmRoles(claims: JsonObject): Set<String> =
        runCatching {
            claims["realm_access"]
                ?.jsonObject
                ?.get("roles")
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

    private fun pad(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }
}

/** Rule-6 denial body: a single ERROR ResponseMessage (proto3-JSON field names). */
internal fun ruleSixDenial(
    code: String,
    message: String,
): JsonObject =
    buildJsonObject {
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("severity", "ERROR")
                        put("code", code)
                        put("humanMessage", message)
                    },
                )
            },
        )
    }

/** Call attribute holding the admitted caller for downstream handlers. */
val AdmittedCallerKey: AttributeKey<AdmittedCaller> = AttributeKey("golem.admittedCaller")

/**
 * Route-scoped plugin enforcing [ShemAdmission] on every call under the route it's
 * installed on (the `/v1` group). On denial it responds with the status + Rule-6
 * body and stops the pipeline; on success it stashes the [AdmittedCaller].
 */
class ShemAdmissionConfig {
    var admission: ShemAdmission? = null
}

val ShemAdmissionPlugin =
    createRouteScopedPlugin("ShemAdmission", ::ShemAdmissionConfig) {
        val admission = requireNotNull(pluginConfig.admission) { "ShemAdmissionPlugin requires an admission instance" }
        onCall { call ->
            when (val result = admission.admit(call.request.headers[HttpHeaders.Authorization])) {
                is AdmissionResult.Admitted -> call.attributes.put(AdmittedCallerKey, result.caller)
                is AdmissionResult.Denied -> {
                    log.info("admission denied ({}) on {}: {}", result.code, call.request.uri, result.message)
                    call.respondText(
                        ruleSixDenial(result.code, result.message).toString(),
                        ContentType.Application.Json,
                        result.status,
                    )
                }
            }
        }
    }

/** Convenience for routes/tests that admit imperatively rather than via the plugin. */
suspend fun ApplicationCall.admitOrRespond(admission: ShemAdmission): AdmittedCaller? =
    when (val result = admission.admit(request.headers[HttpHeaders.Authorization])) {
        is AdmissionResult.Admitted -> result.caller
        is AdmissionResult.Denied -> {
            respondText(
                ruleSixDenial(result.code, result.message).toString(),
                ContentType.Application.Json,
                result.status,
            )
            null
        }
    }
