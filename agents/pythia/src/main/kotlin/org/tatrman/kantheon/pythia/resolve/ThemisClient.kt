package org.tatrman.kantheon.pythia.resolve

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse

/** Themis transport refused the request (401/403) — fail closed (kantheon-security §2.1). */
class ThemisAuthException(
    message: String,
) : RuntimeException(message)

/** Themis was unreachable or returned a non-2xx — investigation fails fast. */
class ThemisUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Calls Themis `understand` (INVESTIGATION_DEEP). REST over Ktor with protobuf as
 * the source of truth (canonical JSON via `JsonFormat`), mirroring iris-bff's
 * `HttpThemisClient`. The user's OBO bearer is forwarded verbatim — never service
 * identity (PD-8).
 */
interface ThemisClient {
    suspend fun understand(
        request: ResolveRequest,
        bearer: String,
    ): ResolveResponse
}

class HttpThemisClient(
    baseUrl: String,
    private val httpClient: HttpClient = HttpClient(CIO),
) : ThemisClient,
    AutoCloseable {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()
    private val resolveUrl = "${baseUrl.trimEnd('/')}/v1/resolve"

    override suspend fun understand(
        request: ResolveRequest,
        bearer: String,
    ): ResolveResponse {
        val response =
            try {
                httpClient.post(resolveUrl) {
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(printer.print(request))
                }
            } catch (e: Exception) {
                throw ThemisUnavailableException("themis unreachable at $resolveUrl", e)
            }
        when {
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw ThemisAuthException("themis rejected the bearer (${response.status})")
            !response.status.isSuccess() ->
                throw ThemisUnavailableException("themis returned ${response.status}")
        }
        val builder = ResolveResponse.newBuilder()
        parser.merge(response.bodyAsText(), builder)
        return builder.build()
    }

    override fun close() = httpClient.close()
}
