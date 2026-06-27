package org.tatrman.kantheon.sysifos.bff.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient

private val LOADER_BODY_METHODS = setOf("POST", "PUT", "PATCH")

/**
 * Excel loader lifecycle proxy (contracts §3.5). Forwards `/loaders/{loader}/...`
 * to the loader service under `/api/v1/...`, injecting the caller's bearer +
 * `X-Tenant-Id`. The body travels as raw bytes with the original `Content-Type`
 * so a multipart `/uploads` keeps its boundary intact; the loader batches to
 * Midas-core itself. The async `commit` goes through the draft path, not here.
 *
 * v1 routes every loader kind to the single configured loader base (excel only);
 * per-loader bases (Google Finance, §4.2) are a later concern (2.6/Phase 3).
 */
fun Route.loaderProxyRoutes(
    loader: MidasCoreClient,
    auth: BearerAuthenticator,
) {
    route("/loaders/{loader}/{path...}") {
        handle {
            val caller = call.requireCaller(auth) ?: return@handle
            val tail =
                call.parameters
                    .getAll("path")
                    ?.joinToString("/")
                    .orEmpty()
            val query =
                call.request.queryParameters
                    .entries()
                    .joinToString("&") { (k, v) -> "$k=${v.first()}" }
            val target = "/api/v1/$tail" + if (query.isNotEmpty()) "?$query" else ""

            val method = call.request.httpMethod
            val resp =
                if (method.value.uppercase() in LOADER_BODY_METHODS) {
                    val bytes = call.receive<ByteArray>()
                    loader.forwardRaw(method, target, caller, bytes, call.request.contentType().toString())
                } else {
                    loader.forward(method, target, caller, null)
                }

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
