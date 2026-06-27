package org.tatrman.kantheon.midas.core.api

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.auth.CallerIdentity
import org.tatrman.kantheon.midas.core.tenant.MissingTenantException
import java.util.UUID

private val protoPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
private val errorJson = Json { encodeDefaults = true }
private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.core.api.Http")

/** 401s and returns null when the bearer is missing/invalid. */
suspend fun ApplicationCall.requireCaller(auth: BearerAuthenticator): CallerIdentity? {
    val caller = auth.authenticate(request.headers[HttpHeaders.Authorization])
    if (caller == null) {
        respondEnvelope(
            HttpStatusCode.Unauthorized,
            ErrorEnvelope(ErrorDetail("UNAUTHORIZED", "Missing or invalid bearer token")),
        )
    }
    return caller
}

/** The request tenant: `X-Tenant-Id` must be present and match the JWT tenant claim. */
fun ApplicationCall.resolveTenant(caller: CallerIdentity): String {
    val header = request.headers["X-Tenant-Id"]?.trim()
    if (header.isNullOrBlank()) {
        throw MidasException(
            MidasErrorCode.TENANT_HEADER_MISSING,
            HttpStatusCode.BadRequest,
            "X-Tenant-Id header is required",
        )
    }
    if (header != caller.tenantId) {
        throw MidasException(
            MidasErrorCode.TENANT_HEADER_JWT_MISMATCH,
            HttpStatusCode.Forbidden,
            "X-Tenant-Id does not match the token tenant claim",
        )
    }
    return header
}

/** Parse a JSON request body into the given proto builder (canonical proto JSON). */
suspend fun <T : Message.Builder> ApplicationCall.receiveProto(builder: T): T {
    JsonFormat.parser().ignoringUnknownFields().merge(receiveText(), builder)
    return builder
}

suspend fun ApplicationCall.respondProto(
    message: Message,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(protoPrinter.print(message), ContentType.Application.Json, status)
}

suspend fun ApplicationCall.respondEnvelope(
    status: HttpStatusCode,
    envelope: ErrorEnvelope,
) {
    respondText(errorJson.encodeToString(ErrorEnvelope.serializer(), envelope), ContentType.Application.Json, status)
}

/** Parse a path segment as a UUID; 400 + null when malformed. */
suspend fun RoutingContext.pathUuid(name: String): UUID? {
    val raw = call.parameters[name]
    val parsed = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (parsed == null) {
        call.respondEnvelope(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorDetail("BAD_REQUEST", "path parameter '$name' is not a valid UUID", name)),
        )
    }
    return parsed
}

/** Maps domain/validation errors to the shared error envelope (contracts §12). */
fun io.ktor.server.application.Application.installMidasErrorPages() {
    install(StatusPages) {
        exception<MidasException> { call, cause ->
            call.respondEnvelope(cause.status, cause.toEnvelope())
        }
        exception<MissingTenantException> { call, cause ->
            call.respondEnvelope(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(
                    ErrorDetail(MidasErrorCode.TENANT_HEADER_MISSING.name, cause.message ?: "tenant required"),
                ),
            )
        }
        // Malformed numeric input (e.g. a non-decimal Money.amount) → 400, not 500.
        exception<NumberFormatException> { call, cause ->
            call.respondEnvelope(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(
                    ErrorDetail(
                        MidasErrorCode.REQUEST_VALIDATION_FAILED.name,
                        "request contains a malformed numeric value",
                    ),
                ),
            )
        }
        exception<Throwable> { call, cause ->
            // Translate Postgres constraint / RLS errors (which arrive wrapped by
            // Exposed) into stable codes before falling back to 500.
            val mapped = cause.toSqlStateError()
            if (mapped != null) {
                call.respondEnvelope(mapped.status, mapped.toEnvelope())
            } else {
                log.error("unhandled error", cause)
                call.respondEnvelope(
                    HttpStatusCode.InternalServerError,
                    ErrorEnvelope(ErrorDetail("INTERNAL", "Unexpected server error")),
                )
            }
        }
    }
}

/**
 * Walk the cause chain for a [java.sql.SQLException] and map its SQLState to a
 * stable [MidasException]. Returns null when the throwable is not a recognised DB
 * error, so the caller falls through to a 500. Covers the races the app-level
 * pre-checks cannot win (a concurrent insert past a duplicate check) and the RLS
 * backstop.
 */
private fun Throwable.toSqlStateError(): MidasException? {
    var t: Throwable? = this
    while (t != null) {
        if (t is java.sql.SQLException) {
            return when (t.sqlState) {
                "23505" ->
                    MidasException.conflict(
                        MidasErrorCode.CONSTRAINT_UNIQUE_VIOLATION,
                        "the value violates a uniqueness constraint",
                    )
                "23503" ->
                    MidasException.conflict(
                        MidasErrorCode.CONSTRAINT_FK_VIOLATION,
                        "the value references a row that does not exist",
                    )
                "42501" ->
                    MidasException.forbidden(
                        MidasErrorCode.RLS_VIOLATION,
                        "the operation is not permitted for the current tenant",
                    )
                else -> null
            }
        }
        t = t.cause
    }
    return null
}
