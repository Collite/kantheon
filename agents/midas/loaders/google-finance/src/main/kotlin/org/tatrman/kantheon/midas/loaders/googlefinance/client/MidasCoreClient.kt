package org.tatrman.kantheon.midas.loaders.googlefinance.client

import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.tatrman.kantheon.midas.v1.FxRate
import org.tatrman.kantheon.midas.v1.FxRateUpsertRequest

/** The write seam to Midas-core (Stage 3.6). FX rates upsert through `POST /fx-rates`
 *  (contracts §2.7); upsert is idempotent on `(from_ccy, to_ccy, rate_date)`. */
fun interface MidasCoreClient {
    suspend fun upsertFxRate(
        rate: FxRate,
        bearer: String,
    )
}

/** Ktor-client [MidasCoreClient] over Midas-core's REST surface. Proto-JSON on the wire
 *  (the proto is the contract even where the transport is REST). */
class HttpMidasCoreClient(
    baseUrl: String,
    private val httpClient: HttpClient = HttpClient(CIO),
) : MidasCoreClient,
    AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()

    override suspend fun upsertFxRate(
        rate: FxRate,
        bearer: String,
    ) {
        val body = printer.print(FxRateUpsertRequest.newBuilder().setFxRate(rate).build())
        val resp =
            httpClient.post("$base/fx-rates") {
                header(HttpHeaders.Authorization, "Bearer $bearer")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (!resp.status.isSuccess()) {
            throw IllegalStateException("midas-core fx-rate upsert failed: ${resp.status} ${resp.bodyAsText()}")
        }
    }

    override fun close() = httpClient.close()
}
