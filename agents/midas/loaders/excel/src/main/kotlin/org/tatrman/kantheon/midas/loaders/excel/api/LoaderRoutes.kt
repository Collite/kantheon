package org.tatrman.kantheon.midas.loaders.excel.api

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.midas.loaders.excel.service.LoaderService
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsResponse
import org.tatrman.kantheon.midas.v1.ListLoaderRunsResponse

/**
 * The Excel-loader REST surface (Stage 1.5 T6 — contracts §4.1): upload → preview →
 * commit + run listing. Every route authenticates the bearer and forwards it (OBO)
 * to Midas-core via [LoaderService].
 */
fun Route.loaderRoutes(
    service: LoaderService,
    auth: BearerAuthenticator,
) {
    post("/uploads") {
        val caller = call.requireCaller(auth) ?: return@post
        var fileBytes: ByteArray? = null
        var brokerId: String? = null
        var portfolioId: String? = null
        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FileItem -> fileBytes = part.provider().readRemaining().readByteArray()
                is PartData.FormItem ->
                    when (part.name) {
                        "broker_id" -> brokerId = part.value
                        "portfolio_id" -> portfolioId = part.value
                    }
                else -> {}
            }
            part.dispose()
        }
        val bytes = fileBytes
        if (bytes == null || brokerId.isNullOrBlank() || portfolioId.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "BAD_REQUEST",
                "file, broker_id, and portfolio_id are required",
            )
            return@post
        }
        val run = service.upload(bytes, brokerId!!, portfolioId!!, caller.callContext())
        call.respondText(
            """{"loader_run_id":"${run.loaderRunId}","status_url":"/api/v1/runs/${run.loaderRunId}"}""",
            io.ktor.http.ContentType.Application.Json,
            HttpStatusCode.Accepted,
        )
    }

    get("/runs") {
        val caller = call.requireCaller(auth) ?: return@get
        val runs = service.listRuns(call.request.queryParameters["portfolio_id"], caller.callContext())
        call.respondProto(ListLoaderRunsResponse.newBuilder().addAllRuns(runs).build())
    }

    get("/runs/{id}") {
        val caller = call.requireCaller(auth) ?: return@get
        val id = call.parameters["id"] ?: return@get
        call.respondProto(service.getRun(id, caller.callContext()))
    }

    get("/runs/{id}/preview") {
        val caller = call.requireCaller(auth) ?: return@get
        val id = call.parameters["id"] ?: return@get
        call.respondProto(service.preview(id, caller.callContext()))
    }

    post("/runs/{id}/commit") {
        val caller = call.requireCaller(auth) ?: return@post
        val id = call.parameters["id"] ?: return@post
        val body = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val skipExisting = body?.get("skip_existing")?.jsonPrimitive?.booleanOrNull ?: true
        val result = service.commit(id, skipExisting, caller.callContext())
        call.respondProto(
            BatchInsertTransactionsResponse
                .newBuilder()
                .setInsertedCount(result.inserted)
                .setSkippedCount(result.skipped)
                .setFailedCount(result.failed)
                .build(),
        )
    }
}
