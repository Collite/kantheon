package org.tatrman.kantheon.sysifos.bff.midas

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.bffbase.tenant.TenantHeaderForwarder

/** A forwarded Midas-core response: status + raw body (proto-JSON) + content type. */
data class MidasResponse(
    val status: Int,
    val body: String,
    val contentType: String?,
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Ktor HTTP client to Midas-core (contracts §2–§3.3). Sysifos has no DB — every
 * read/write proxies here. Caller identity travels as the forwarded `Authorization`
 * bearer + `X-Tenant-Id` (from the JWT claim), never as service identity
 * (kantheon-security). Used by the sync CRUD proxy, the draft committer, and the
 * `/ready` reachability ping.
 */
class MidasCoreClient(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient(CIO),
) {
    private val log = LoggerFactory.getLogger(MidasCoreClient::class.java)

    /** True when Midas-core answers its `/health` probe — drives `/ready`. */
    suspend fun reachable(): Boolean =
        runCatching {
            val resp: HttpResponse = http.get("$baseUrl/health")
            resp.status.isSuccess()
        }.getOrElse {
            log.warn("sysifos-bff: Midas-core unreachable at {}: {}", baseUrl, it.message)
            false
        }

    /**
     * Forward a request to Midas-core, injecting the caller's bearer + tenant
     * header. [path] is the Midas REST path (e.g. `/api/v1/clients`). Returns the
     * status + raw body for transparent passthrough; never throws on a 4xx/5xx
     * (the caller surfaces it).
     */
    suspend fun forward(
        method: HttpMethod,
        path: String,
        caller: CallerIdentity,
        body: String? = null,
    ): MidasResponse {
        val resp =
            http.request("$baseUrl$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer ${caller.bearer}")
                val (name, value) = TenantHeaderForwarder.header(caller)
                header(name, value)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        return MidasResponse(
            status = resp.status.value,
            body = resp.bodyAsText(),
            contentType = resp.headers[HttpHeaders.ContentType],
        )
    }

    /**
     * Forward a request with a raw byte body and a passthrough content type — used
     * for the Excel loader's multipart upload (the `Content-Type` carries the
     * multipart boundary, so it must travel verbatim). [contentType] null means no
     * body. Otherwise identical to [forward].
     */
    suspend fun forwardRaw(
        method: HttpMethod,
        path: String,
        caller: CallerIdentity,
        body: ByteArray?,
        contentType: String?,
    ): MidasResponse {
        val resp =
            http.request("$baseUrl$path") {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer ${caller.bearer}")
                val (name, value) = TenantHeaderForwarder.header(caller)
                header(name, value)
                if (body != null && contentType != null) {
                    contentType(ContentType.parse(contentType))
                    setBody(body)
                }
            }
        return MidasResponse(
            status = resp.status.value,
            body = resp.bodyAsText(),
            contentType = resp.headers[HttpHeaders.ContentType],
        )
    }

    fun close() = http.close()
}
