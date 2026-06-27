package org.tatrman.kantheon.midas.core.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * Stable error-code strings (contracts §12). The wire envelope is
 * `{ "error": { code, message, field?, details? }, "messages": [] }`.
 */
enum class MidasErrorCode {
    CLIENT_NOT_FOUND,
    PORTFOLIO_NOT_FOUND,
    ASSET_NOT_FOUND,
    TRANSACTION_NOT_FOUND,
    TRANSACTION_DUPLICATE_EXTERNAL_ID,
    TRANSACTION_VALIDATION_FAILED,
    BALANCE_ENTRY_PORTFOLIO_OR_ASSET_NOT_FOUND,
    BALANCE_ENTRY_NO_DIFF,
    TENANT_HEADER_MISSING,
    TENANT_HEADER_JWT_MISMATCH,
    RLS_VIOLATION,
    CONSTRAINT_UNIQUE_VIOLATION,
    CONSTRAINT_FK_VIOLATION,
    REQUEST_VALIDATION_FAILED,
    LOADER_RUN_NOT_FOUND,
    LOADER_RUN_INVALID_STATE,
    RECONCILE_DIFF_NOT_FOUND,
    REPORT_TEMPLATE_NOT_FOUND,
    REPORT_PARAM_INVALID,
    FX_RATE_NOT_FOUND,
}

/** A domain/HTTP error carrying a stable [MidasErrorCode] and the status to emit. */
class MidasException(
    val code: MidasErrorCode,
    val status: HttpStatusCode,
    override val message: String,
    val field: String? = null,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    companion object {
        fun notFound(
            code: MidasErrorCode,
            message: String,
        ) = MidasException(code, HttpStatusCode.NotFound, message)

        fun conflict(
            code: MidasErrorCode,
            message: String,
            field: String? = null,
            details: Map<String, String> = emptyMap(),
        ) = MidasException(code, HttpStatusCode.Conflict, message, field, details)

        fun forbidden(
            code: MidasErrorCode,
            message: String,
        ) = MidasException(code, HttpStatusCode.Forbidden, message)

        fun badRequest(
            code: MidasErrorCode,
            message: String,
            field: String? = null,
        ) = MidasException(code, HttpStatusCode.BadRequest, message, field)
    }
}

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val field: String? = null,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ErrorEnvelope(
    val error: ErrorDetail,
    val messages: List<String> = emptyList(),
)

fun MidasException.toEnvelope(): ErrorEnvelope = ErrorEnvelope(ErrorDetail(code.name, message, field, details))
