@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.providers.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The Kantheon **llm-gateway** HTTP client (P2 Stage 2.2; contracts §5.2). This
 * is *not* a new provider — it is the same [OpenAiCompatProvider] pointed at the
 * gateway with the auth + cost-attribution headers the BYOK client doesn't send.
 * Only the base URL + headers change (`llm.source = gateway`); the BYOK path
 * under `local` is untouched.
 *
 * Headers on every gateway request (PD-11; the gateway may ignore them — Hebe
 * never depends on a response echo):
 *  - `Authorization: Bearer <gateway key>`
 *  - `X-Cost-Center: hebe/<instance_id>` (static, set at client construction)
 *  - `X-Turn-Ref: <turn/job id>` (per-call, from the [TurnRefContext] coroutine
 *    element; omitted for ad-hoc console chat where no turn ref is in scope)
 */
object GatewayClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val REQUEST_TIMEOUT_MS = 300_000L
    private const val MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 500L
    private const val RETRY_DELAY_FACTOR = 200L
    private const val JITTER_MAX_MS = 1000L

    const val HEADER_COST_CENTER = "X-Cost-Center"
    const val HEADER_TURN_REF = "X-Turn-Ref"

    /**
     * Builds the gateway client. [engine] is injectable so specs can pass a ktor
     * `MockEngine`; production passes `null` (CIO).
     */
    fun build(
        apiKey: String,
        costCenter: String,
        engine: HttpClientEngine? = null,
        logger: Logger? = null,
    ): HttpClient {
        val block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(GatewayClient.json) }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            install(HttpRequestRetry) {
                maxRetries = MAX_RETRIES
                retryOnServerErrors(maxRetries = MAX_RETRIES)
                retryIf { _, response ->
                    val status = response.status.value
                    status in 500..599 || status == 429
                }
                delayMillis { retryCount ->
                    val jitter = (Math.random() * JITTER_MAX_MS).toLong()
                    BASE_DELAY_MS + retryCount * RETRY_DELAY_FACTOR + jitter
                }
            }
            install(DefaultRequest) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HEADER_COST_CENTER, costCenter)
            }
            install(costAttributionPlugin)
            if (logger != null) {
                install(Logging) {
                    this.logger = logger
                    level = LogLevel.HEADERS
                }
            }
        }
        return if (engine != null) HttpClient(engine, block) else HttpClient(CIO, block)
    }

    /** `hebe/<instance_id>` — the value of [HEADER_COST_CENTER]. */
    fun costCenter(instanceId: String): String = "hebe/$instanceId"

    /**
     * Appends `X-Turn-Ref` from the current coroutine's [TurnRefContext], if any.
     * `onRequest` is suspending, so it reads the caller's coroutine context — the
     * turn ref set by [withTurnRef] around the chat call.
     */
    private val costAttributionPlugin =
        createClientPlugin("HebeCostAttribution") {
            onRequest { request, _ ->
                val ref = coroutineContext[TurnRefContext.Key]?.turnRef
                if (!ref.isNullOrBlank()) {
                    request.headers.append(HEADER_TURN_REF, ref)
                }
                // W3C trace-context (P2 Stage 2.4 T4): inject `traceparent`/
                // `tracestate` from the current OTel context so a single trace
                // spans cron-tick → gateway → … (no active span ⇒ no-op).
                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
                    .getInstance()
                    .inject(io.opentelemetry.context.Context.current(), request) { req, key, value ->
                        req?.headers?.append(key, value)
                    }
            }
        }
}

/**
 * Carries the firing turn/job id through the coroutine context so the gateway
 * client can stamp `X-Turn-Ref` without threading the ref through every
 * provider call. Set with [withTurnRef] around a reasoning turn.
 */
class TurnRefContext(
    val turnRef: String?,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TurnRefContext>
}

/** Runs [block] with [turnRef] bound for any gateway calls it makes. */
suspend fun <T> withTurnRef(
    turnRef: String?,
    block: suspend () -> T,
): T = withContext(TurnRefContext(turnRef)) { block() }
