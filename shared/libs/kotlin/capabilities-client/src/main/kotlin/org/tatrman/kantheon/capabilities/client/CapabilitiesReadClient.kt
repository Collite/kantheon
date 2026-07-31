package org.tatrman.kantheon.capabilities.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The outbound capabilities-registry client configuration, in one place so the
 * wiring under test is the wiring that ships (PT P0·S0.1 T1). [otel] is null
 * exactly when the consuming service has `telemetry.enabled=false` — then no
 * plugin is installed and no `traceparent` leaves the process.
 */
internal fun <T : HttpClientEngineConfig> capabilitiesHttpClient(
    engineFactory: HttpClientEngineFactory<T>,
    otel: OpenTelemetry?,
    engineConfig: T.() -> Unit = {},
): HttpClient =
    HttpClient(engineFactory) {
        engine(engineConfig)
        install(ContentNegotiation) { json() }
        otel?.let { sdk -> install(KtorClientTelemetry) { setOpenTelemetry(sdk) } }
    }

/**
 * Read-mostly client for consumers (Themis, Iris-BFF). Calls capabilities-mcp via the
 * REST mirror; caches responses for [cacheTtlMs] to avoid pounding the registry.
 *
 * Throws [CapabilitiesUnreachableException] on cache-miss + endpoint unreachable. Callers
 * decide whether to fail-fast (Themis at boot per §4.4) or warn-and-continue.
 */
class CapabilitiesReadClient(
    private val endpoint: String,
    private val cacheTtlMs: Long = 30_000,
    otel: OpenTelemetry? = null,
    private val httpClient: HttpClient = capabilitiesHttpClient(CIO, otel),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun listAgents(): JsonObject = cachedGet("/v1/capabilities/agents")

    suspend fun list(): JsonObject = cachedGet("/v1/capabilities")

    suspend fun get(id: String): JsonObject = cachedGet("/v1/capabilities/$id")

    fun invalidateCache() {
        cache.clear()
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun cachedGet(path: String): JsonObject {
        val now = Instant.now(clock)
        val cached = cache[path]
        if (cached != null && Duration.between(cached.fetchedAt, now).toMillis() < cacheTtlMs) {
            return cached.payload
        }
        val resp: HttpResponse =
            try {
                httpClient.get("${endpoint.trimEnd('/')}$path")
            } catch (e: Throwable) {
                throw CapabilitiesUnreachableException(path, e)
            }
        if (resp.status != HttpStatusCode.OK) {
            throw CapabilitiesUnreachableException(
                path,
                IllegalStateException("non-OK status ${resp.status}"),
            )
        }
        val payload: JsonObject = resp.body()
        cache[path] = CacheEntry(payload = payload, fetchedAt = now)
        return payload
    }

    private data class CacheEntry(
        val payload: JsonObject,
        val fetchedAt: Instant,
    )
}

class CapabilitiesUnreachableException(
    path: String,
    cause: Throwable,
) : RuntimeException("capabilities-mcp unreachable at $path: ${cause.message}", cause)
