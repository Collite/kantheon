package org.tatrman.kantheon.iris.routing

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import org.tatrman.kantheon.themis.v1.Themis.ResolveRequest
import org.tatrman.kantheon.themis.v1.Themis.ResolveResponse

/**
 * The outbound Themis client configuration, in one place so the wiring under test
 * is the wiring that ships (PT P0·S0.1 T1). [otel] is null exactly when
 * `telemetry.enabled=false` — then no plugin is installed and no `traceparent`
 * leaves the process.
 */
internal fun <T : HttpClientEngineConfig> themisHttpClient(
    engineFactory: HttpClientEngineFactory<T>,
    timeoutMs: Long,
    otel: OpenTelemetry?,
    engineConfig: T.() -> Unit = {},
): HttpClient =
    HttpClient(engineFactory) {
        engine(engineConfig)
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
        }
        otel?.let { sdk -> install(KtorClientTelemetry) { setOpenTelemetry(sdk) } }
    }

/**
 * Ktor-client [ThemisClient] over Themis `POST /v1/resolve`. Protobuf is the
 * source of truth even on a REST wire (kantheon-architecture §4 wire policy):
 * `ResolveRequest`/`ResolveResponse` ride as proto-canonical JSON
 * (`JsonFormat`), not a hand-rolled shape. The path is co-owned with Themis
 * Stage 3.6 — both sides use `/v1/resolve`.
 *
 * Live-HTTP fidelity is an integration concern; the unit gate drives this
 * against a Ktor `MockEngine` and the component gate against Wiremock-Themis.
 */
class HttpThemisClient(
    private val baseUrl: String,
    timeoutMs: Long = 10_000,
    otel: OpenTelemetry? = null,
    private val httpClient: HttpClient = themisHttpClient(CIO, timeoutMs, otel),
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
                    // OBO: forward the caller's bearer verbatim (never a service token).
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(printer.print(request))
                }
            } catch (e: Throwable) {
                throw ThemisUnavailableException("Themis unreachable at $resolveUrl: ${e.message}", e)
            }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            // Fail closed on an expired/invalid OBO bearer — distinct from an outage.
            throw ThemisAuthException("Themis rejected the bearer (${response.status}) at $resolveUrl")
        }
        if (!response.status.isSuccess()) {
            throw ThemisUnavailableException("Themis returned ${response.status} from $resolveUrl")
        }
        val builder = ResolveResponse.newBuilder()
        parser.merge(response.bodyAsText(), builder)
        return builder.build()
    }

    override fun close() = httpClient.close()
}
