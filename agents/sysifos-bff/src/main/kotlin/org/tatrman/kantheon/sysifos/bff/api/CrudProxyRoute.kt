package org.tatrman.kantheon.sysifos.bff.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.bffbase.auth.requireRole
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient

/** Methods that carry a request body to forward. */
private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

/** Role gating admin-only proxy paths (contracts §3.3). */
private const val ADMIN_ROLE = "midas:admin"
private val ASSET_ADMIN_METHODS = setOf("PATCH", "DELETE")

/**
 * Sync CRUD proxy (contracts §3.3). Forwards the `/midas/...` paths (reads +
 * single-record writes) to Midas-core under `/api/v1/...`, injecting the caller's
 * bearer + `X-Tenant-Id` (in `MidasCoreClient.forward`). The response status +
 * body pass through transparently — Sysifos surfaces Midas-core errors as-is.
 * Bulk/import use the async draft path (`/drafts`), not this proxy.
 *
 * Server-side role gating (contracts §3.3): the audit log is admin-only and asset
 * *edits* (`PATCH`/`DELETE /midas/assets*`) require `midas:admin` — Midas-core
 * applies no RBAC, so the BFF is the enforcement point and must not rely on the
 * FE-only gate. `POST /midas/assets` is left ungated: the admin Assets screen and
 * the ungated in-flow quick-create share that endpoint, so it can't be told apart
 * by path here (it stays FE-gated by context).
 */
fun Route.crudProxyRoutes(
    midas: MidasCoreClient,
    auth: BearerAuthenticator,
) {
    route("/midas/{path...}") {
        handle {
            val caller = call.requireCaller(auth) ?: return@handle
            val tail =
                call.parameters
                    .getAll("path")
                    ?.joinToString("/")
                    .orEmpty()

            val method = call.request.httpMethod
            val adminRequired =
                tail.startsWith("audit") ||
                    (tail.startsWith("assets") && method.value.uppercase() in ASSET_ADMIN_METHODS)
            if (adminRequired && !call.requireRole(caller, ADMIN_ROLE)) return@handle

            val query =
                call.request.queryParameters
                    .entries()
                    .joinToString("&") { (k, v) -> "$k=${v.first()}" }
            val target = "/api/v1/$tail" + if (query.isNotEmpty()) "?$query" else ""

            val body = if (method.value.uppercase() in BODY_METHODS) call.receiveText() else null

            val resp = midas.forward(method, target, caller, body)
            call.respondText(
                resp.body,
                contentType =
                    resp.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                        ?: ContentType.Application.Json,
                status = HttpStatusCode.fromValue(resp.status),
            )
        }
    }
}
