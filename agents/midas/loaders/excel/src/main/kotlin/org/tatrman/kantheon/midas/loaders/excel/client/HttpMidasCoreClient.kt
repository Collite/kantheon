package org.tatrman.kantheon.midas.loaders.excel.client

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import org.tatrman.kantheon.midas.v1.Asset
import org.tatrman.kantheon.midas.v1.AssetKind
import org.tatrman.kantheon.midas.v1.AssetResponse
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsRequest
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsResponse
import org.tatrman.kantheon.midas.v1.CreateAssetRequest
import org.tatrman.kantheon.midas.v1.ListAssetsResponse
import org.tatrman.kantheon.midas.v1.ListTransactionsResponse
import org.tatrman.kantheon.midas.v1.Transaction

/**
 * REST [MidasCoreClient] (Stage 1.5 T6). Talks to Midas-core's `/api/v1` over proto
 * JSON, forwarding the caller's bearer + `X-Tenant-Id` (OBO) so RLS scopes every
 * write. Asset resolution is find-by-symbol then create-if-absent.
 */
class HttpMidasCoreClient(
    private val baseUrl: String,
    private val http: HttpClient,
) : MidasCoreClient {
    private val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    private val parser = JsonFormat.parser().ignoringUnknownFields()

    override suspend fun resolveAsset(
        symbol: String,
        currency: String,
        ctx: CallContext,
    ): String {
        val listed =
            http
                .get("$baseUrl/api/v1/assets?symbol=${symbol.encodeURLParameter()}") { auth(ctx) }
                .require("list assets")
        ListAssetsResponse
            .newBuilder()
            .also { parser.merge(listed, it) }
            .build()
            .assetsList
            .firstOrNull { it.symbol == symbol }
            ?.let { return it.assetId }

        val kind = if (symbol.startsWith("CASH.")) AssetKind.ASSET_CASH else AssetKind.ASSET_STOCK
        val req =
            CreateAssetRequest
                .newBuilder()
                .setAsset(
                    Asset
                        .newBuilder()
                        .setSymbol(symbol)
                        .setName(symbol)
                        .setKind(kind)
                        .setCurrency(currency)
                        .build(),
                ).build()
        val created =
            http
                .post("$baseUrl/api/v1/assets") {
                    auth(ctx)
                    json(req)
                }.require("create asset")
        return AssetResponse
            .newBuilder()
            .also { parser.merge(created, it) }
            .build()
            .asset.assetId
    }

    override suspend fun existingExternalIds(
        portfolioId: String,
        ctx: CallContext,
    ): Set<String> {
        val body =
            http
                .get(
                    "$baseUrl/api/v1/transactions?portfolio_id=${portfolioId.encodeURLParameter()}&size=500",
                ) { auth(ctx) }
                .require("list transactions")
        return ListTransactionsResponse
            .newBuilder()
            .also { parser.merge(body, it) }
            .build()
            .transactionsList
            .mapNotNull { it.externalId.ifBlank { null } }
            .toSet()
    }

    override suspend fun batchInsert(
        transactions: List<Transaction>,
        skipExisting: Boolean,
        ctx: CallContext,
    ): BatchResult {
        val req =
            BatchInsertTransactionsRequest
                .newBuilder()
                .addAllTransactions(transactions)
                .setSkipExisting(skipExisting)
                .build()
        val body =
            http
                .post("$baseUrl/api/v1/transactions:batch") {
                    auth(ctx)
                    json(req)
                }.require("batch insert")
        val resp = BatchInsertTransactionsResponse.newBuilder().also { parser.merge(body, it) }.build()
        return BatchResult(resp.insertedCount, resp.skippedCount, resp.failedCount)
    }

    private fun HttpRequestBuilder.auth(ctx: CallContext) {
        header(HttpHeaders.Authorization, "Bearer ${ctx.bearer}")
        header("X-Tenant-Id", ctx.tenantId)
    }

    private fun HttpRequestBuilder.json(message: Message) {
        contentType(ContentType.Application.Json)
        setBody(printer.print(message))
    }

    private suspend fun HttpResponse.require(action: String): String {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            throw MidasCoreCallException("midas-core $action failed: HTTP $status — $text")
        }
        return text
    }
}

/** Raised when a Midas-core call returns a non-2xx status. */
class MidasCoreCallException(
    message: String,
) : RuntimeException(message)
