@file:Suppress("TooGenericExceptionCaught", "ThrowsCount", "MagicNumber", "NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.host

import org.tatrman.kantheon.hebe.plugin.api.GatedHttpClient
import org.tatrman.kantheon.hebe.plugin.api.HttpResponse as PluginHttpResponse
import org.tatrman.kantheon.hebe.plugin.api.PluginCapabilityException
import org.tatrman.kantheon.hebe.plugin.api.PluginManifest
import org.tatrman.kantheon.hebe.plugin.api.SecretHandle
import org.tatrman.kantheon.hebe.security.policy.SsrfGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI

class GatedHttpClientImpl(
    private val manifest: PluginManifest,
    private val secretResolver: (String) -> String?,
    private val ssrfGuard: SsrfGuard,
) : GatedHttpClient,
    AutoCloseable {
    private val httpClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
        }

    private val allowedDomains: Set<String> by lazy {
        manifest.allowlistDomains.toSet()
    }

    private fun validateUrl(url: String) {
        val ssrfResult = ssrfGuard.isBlocked(url)
        if (ssrfResult !is SsrfGuard.SsrfResult.Allowed) {
            val reason =
                when (ssrfResult) {
                    is SsrfGuard.SsrfResult.Blocked -> ssrfResult.reason
                    is SsrfGuard.SsrfResult.Invalid -> ssrfResult.reason
                    else -> "blocked"
                }
            throw SecurityException("SSRF guard blocked: $reason")
        }
        val uri = URI(url)
        val host = uri.host ?: throw PluginCapabilityException("Invalid URL: $url")
        if (allowedDomains.isNotEmpty() && host !in allowedDomains) {
            throw PluginCapabilityException(
                "URL host '$host' not in plugin allowlist: $allowedDomains",
            )
        }
    }

    private fun appendAuth(
        headers: MutableMap<String, String>,
        auth: SecretHandle?,
    ) {
        if (auth != null) {
            val value =
                secretResolver(auth.name)
                    ?: throw PluginCapabilityException("Secret '${auth.name}' not found")
            headers[HttpHeaders.Authorization] = "Bearer $value"
        }
    }

    private fun buildHeadersMap(
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): MutableMap<String, String> {
        val result = inputHeaders.toMutableMap()
        appendAuth(result, auth)
        return result
    }

    private suspend fun doGet(
        url: String,
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse {
        validateUrl(url)
        val h = buildHeadersMap(inputHeaders, auth)
        val resp =
            httpClient.get(url) {
                h.forEach { (name, value) -> headers.append(name, value) }
            }
        val respHeaders = mutableMapOf<String, List<String>>()
        resp.headers.forEach { name, values -> respHeaders[name] = values }
        return PluginHttpResponse(
            status = resp.status.value,
            headers = respHeaders,
            body = resp.bodyAsText().toByteArray(),
        )
    }

    private suspend fun doPost(
        url: String,
        body: ByteArray,
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse {
        validateUrl(url)
        val h = buildHeadersMap(inputHeaders, auth)
        val resp =
            httpClient.post(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(body)
                h.forEach { (name, value) -> headers.append(name, value) }
            }
        val respHeaders = mutableMapOf<String, List<String>>()
        resp.headers.forEach { name, values -> respHeaders[name] = values }
        return PluginHttpResponse(
            status = resp.status.value,
            headers = respHeaders,
            body = resp.bodyAsText().toByteArray(),
        )
    }

    private suspend fun doPut(
        url: String,
        body: ByteArray,
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse {
        validateUrl(url)
        val h = buildHeadersMap(inputHeaders, auth)
        val resp =
            httpClient.put(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(body)
                h.forEach { (name, value) -> headers.append(name, value) }
            }
        val respHeaders = mutableMapOf<String, List<String>>()
        resp.headers.forEach { name, values -> respHeaders[name] = values }
        return PluginHttpResponse(
            status = resp.status.value,
            headers = respHeaders,
            body = resp.bodyAsText().toByteArray(),
        )
    }

    private suspend fun doDelete(
        url: String,
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse {
        validateUrl(url)
        val h = buildHeadersMap(inputHeaders, auth)
        val resp =
            httpClient.delete(url) {
                h.forEach { (name, value) -> headers.append(name, value) }
            }
        val respHeaders = mutableMapOf<String, List<String>>()
        resp.headers.forEach { name, values -> respHeaders[name] = values }
        return PluginHttpResponse(
            status = resp.status.value,
            headers = respHeaders,
            body = resp.bodyAsText().toByteArray(),
        )
    }

    private suspend fun doPatch(
        url: String,
        body: ByteArray,
        inputHeaders: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse {
        validateUrl(url)
        val h = buildHeadersMap(inputHeaders, auth)
        val resp =
            httpClient.patch(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(body)
                h.forEach { (name, value) -> headers.append(name, value) }
            }
        val respHeaders = mutableMapOf<String, List<String>>()
        resp.headers.forEach { name, values -> respHeaders[name] = values }
        return PluginHttpResponse(
            status = resp.status.value,
            headers = respHeaders,
            body = resp.bodyAsText().toByteArray(),
        )
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse = doGet(url, headers, auth)

    override suspend fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse = doPost(url, body, headers, auth)

    override suspend fun put(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse = doPut(url, body, headers, auth)

    override suspend fun delete(
        url: String,
        headers: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse = doDelete(url, headers, auth)

    override suspend fun patch(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        auth: SecretHandle?,
    ): PluginHttpResponse = doPatch(url, body, headers, auth)

    override fun close() = httpClient.close()
}
