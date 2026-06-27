@file:Suppress("MagicNumber")

package org.tatrman.kantheon.hebe.providers.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
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

    fun create(
        apiKey: String,
        logger: Logger? = null,
    ): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(HttpClientFactory.json)
            }

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

            if (logger != null) {
                install(Logging) {
                    this.logger = logger
                    level = LogLevel.HEADERS
                }
            }
        }
}
