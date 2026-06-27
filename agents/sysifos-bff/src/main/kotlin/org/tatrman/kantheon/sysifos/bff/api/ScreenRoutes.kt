package org.tatrman.kantheon.sysifos.bff.api

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.bff.screen.assembleImportScreen
import org.tatrman.kantheon.sysifos.bff.screen.assembleTransactionsScreen

/** Transaction-list filter params forwarded verbatim to Midas-core `/transactions`. */
private val TX_PASSTHROUGH = listOf("portfolio_id", "asset_id", "from", "to", "kind", "page", "size")

/**
 * Read fan-out routes (contracts §3.4). The BFF makes several Midas-core calls and
 * assembles the result to cut FE round-trips. `/screens/transactions` fetches the
 * portfolios list, the assets list, and a transactions page, then nests derived
 * cash legs under their security leg ([assembleTransactionsScreen]). All three
 * forwards carry the caller's bearer + tenant header; a failed leg surfaces its
 * Midas-core status transparently.
 */
fun Route.screenRoutes(
    midas: MidasCoreClient,
    loader: MidasCoreClient,
    auth: BearerAuthenticator,
) {
    get("/screens/transactions") {
        val caller = call.requireCaller(auth) ?: return@get
        val q = call.request.queryParameters
        val txQuery =
            TX_PASSTHROUGH
                .mapNotNull { k -> q[k]?.let { "$k=$it" } }
                .joinToString("&")

        val txResp =
            midas.forward(
                HttpMethod.Get,
                "/api/v1/transactions" + if (txQuery.isNotEmpty()) "?$txQuery" else "",
                caller,
            )
        if (!txResp.isSuccess) {
            call.respondText(txResp.body, ContentType.Application.Json, HttpStatusCode.fromValue(txResp.status))
            return@get
        }
        // The two dictionary legs are independent — fetch them concurrently.
        val (assetsResp, portfoliosResp) =
            coroutineScope {
                val a = async { midas.forward(HttpMethod.Get, "/api/v1/assets?size=1000", caller) }
                val p = async { midas.forward(HttpMethod.Get, "/api/v1/portfolios?size=1000", caller) }
                a.await() to p.await()
            }
        // A failed dictionary leg surfaces its Midas-core status rather than silently
        // degrading to an empty array (which would hide resolvable asset/portfolio names).
        for (leg in listOf(assetsResp, portfoliosResp)) {
            if (!leg.isSuccess) {
                call.respondText(leg.body, ContentType.Application.Json, HttpStatusCode.fromValue(leg.status))
                return@get
            }
        }

        val assembled = assembleTransactionsScreen(txResp.body, assetsResp.body, portfoliosResp.body)
        call.respondText(assembled, ContentType.Application.Json, HttpStatusCode.OK)
    }

    get("/screens/import/{loaderRunId}") {
        val caller = call.requireCaller(auth) ?: return@get
        val runId = call.parameters["loaderRunId"].orEmpty()
        val runResp = loader.forward(HttpMethod.Get, "/api/v1/runs/$runId", caller)
        if (!runResp.isSuccess) {
            call.respondText(runResp.body, ContentType.Application.Json, HttpStatusCode.fromValue(runResp.status))
            return@get
        }
        val previewResp = loader.forward(HttpMethod.Get, "/api/v1/runs/$runId/preview", caller)

        val assembled = assembleImportScreen(runResp.body, previewResp.body)
        call.respondText(assembled, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
