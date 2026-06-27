package org.tatrman.kantheon.midas.loaders.excel.api

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.excel.client.MidasCoreCallException
import org.tatrman.kantheon.midas.loaders.excel.parser.UnknownBrokerException
import org.tatrman.kantheon.midas.loaders.excel.service.LoaderRunNotFoundException

private val protoPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.loaders.excel.api.Http")

/** 401s + returns null when the bearer is missing/invalid. */
suspend fun ApplicationCall.requireCaller(auth: BearerAuthenticator): CallerIdentity? {
    val caller = auth.authenticate(request.headers[HttpHeaders.Authorization])
    if (caller == null) {
        respondError(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Missing or invalid bearer token")
    }
    return caller
}

suspend fun ApplicationCall.respondProto(
    message: Message,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(protoPrinter.print(message), ContentType.Application.Json, status)

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respondText("""{"error":{"code":"$code","message":${jsonString(message)}}}""", ContentType.Application.Json, status)

private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Maps loader errors to HTTP (contracts §12 envelope shape). */
fun Application.installLoaderErrorPages() {
    install(StatusPages) {
        exception<UnknownBrokerException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "UNKNOWN_BROKER", cause.message ?: "unknown broker")
        }
        exception<LoaderRunNotFoundException> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "LOADER_RUN_NOT_FOUND", cause.message ?: "not found")
        }
        exception<MidasCoreCallException> { call, cause ->
            // Midas-core rejected an OBO write (e.g. RLS / validation) — surface as 502.
            call.respondError(HttpStatusCode.BadGateway, "MIDAS_CORE_ERROR", cause.message ?: "midas-core error")
        }
        exception<Throwable> { call, cause ->
            log.error("unhandled loader error", cause)
            call.respondError(HttpStatusCode.InternalServerError, "INTERNAL", "Unexpected server error")
        }
    }
}
