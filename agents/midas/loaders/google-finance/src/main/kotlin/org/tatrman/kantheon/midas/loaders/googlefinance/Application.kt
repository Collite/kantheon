package org.tatrman.kantheon.midas.loaders.googlefinance

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.midas.loaders.googlefinance.poller.FxRatePoller
import org.tatrman.kantheon.midas.loaders.googlefinance.poller.PricePoller
import org.tatrman.kantheon.midas.loaders.googlefinance.service.LoaderRun
import org.tatrman.kantheon.midas.loaders.googlefinance.service.LoaderService
import org.tatrman.kantheon.midas.loaders.googlefinance.service.RunKind
import org.tatrman.kantheon.midas.loaders.googlefinance.service.RunStore
import org.tatrman.kantheon.midas.loaders.googlefinance.sheets.FixtureSheetsSource

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.midas.loaders.googlefinance.Application")

fun main() {
    // Live seams (Google Sheets fetch, the portfolio/asset pair sources, the asset_prices sink,
    // and the Quartz cron binding @ 23:00 / 23:30 UTC) are integration-deferred — wired from
    // config/env in Stream T. v1 boot stands up the HTTP control surface with empty fixtures so
    // the probes + manual :trigger work; the schedule is a deploy-time binding over LoaderService.
    val runs = RunStore()
    val sheets = FixtureSheetsSource(emptyMap())
    val service =
        LoaderService(
            fx = FxRatePoller(pairs = { emptyList() }, sheets = sheets, midas = { _, _ -> }),
            prices = PricePoller(assets = { emptyList() }, sheets = sheets, sink = { _, _ -> }),
            runs = runs,
            bearer = { "" },
        )
    val port = System.getenv("GOOGLE_FINANCE_PORT")?.toIntOrNull() ?: 7316
    log.info("midas-google-finance-loader starting on :{} (HTTP; pollers via in-process scheduler)", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(service, runs) }.start(wait = true)
}

fun Application.module(
    service: LoaderService,
    runs: RunStore,
) {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun io.ktor.server.application.ApplicationCall.json(
        status: HttpStatusCode,
        body: JsonObject,
    ) = respondText(body.toString(), ContentType.Application.Json, status)

    routing {
        get("/health") { call.respondText("OK") }
        get("/ready") { call.respondText("OK") }

        // Manual trigger (contracts §4.2): {"kind":"fx_rates"|"market_prices"}.
        post("/runs:trigger") {
            val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
            val kind =
                when (body?.get("kind")?.jsonPrimitive?.content) {
                    "fx_rates" -> RunKind.FX_RATES
                    "market_prices" -> RunKind.MARKET_PRICES
                    else -> null
                }
            if (kind == null) {
                call.json(
                    HttpStatusCode.BadRequest,
                    buildJsonObject { put("error", "kind must be fx_rates | market_prices") },
                )
                return@post
            }
            call.json(HttpStatusCode.Accepted, service.trigger(kind).toJson())
        }

        get(
            "/runs",
        ) { call.respondText(JsonArray(runs.list().map { it.toJson() }).toString(), ContentType.Application.Json) }
        get("/runs/{id}") {
            val run = call.parameters["id"]?.let { runs.get(it) }
            if (run == null) {
                call.json(HttpStatusCode.NotFound, buildJsonObject { put("error", "run not found") })
            } else {
                call.json(HttpStatusCode.OK, run.toJson())
            }
        }
    }
}

private fun LoaderRun.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("kind", kind.name.lowercase())
        put("status", status)
        put("started_at", startedAt.toString())
        put("finished_at", finishedAt?.toString()?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
        put("requested", requested)
        put("processed", processed)
        put("skipped", skipped)
        put("message", message)
    }
