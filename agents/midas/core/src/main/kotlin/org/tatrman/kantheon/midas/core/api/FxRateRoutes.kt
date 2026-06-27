package org.tatrman.kantheon.midas.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.FxRateRepository
import org.tatrman.kantheon.midas.v1.FxRateResponse
import org.tatrman.kantheon.midas.v1.FxRateUpsertRequest
import org.tatrman.kantheon.midas.v1.ListFxRatesResponse
import java.time.LocalDate

/**
 * FX rates REST surface (contracts §2.7). fx_rates is global — a valid caller is
 * required, but there is no per-tenant scoping (no `resolveTenant`).
 */
fun Route.fxRateRoutes(
    repo: FxRateRepository,
    auth: BearerAuthenticator,
) {
    route("/fx-rates") {
        post {
            call.requireCaller(auth) ?: return@post
            val req = call.receiveProto(FxRateUpsertRequest.newBuilder()).build()
            val saved = repo.upsert(req.fxRate)
            call.respondProto(FxRateResponse.newBuilder().setFxRate(saved).build())
        }

        get {
            call.requireCaller(auth) ?: return@get
            val q = call.request.queryParameters
            val rates =
                repo.list(
                    q["from_ccy"],
                    q["to_ccy"],
                    q["from_date"]?.let { LocalDate.parse(it) },
                    q["to_date"]?.let { LocalDate.parse(it) },
                )
            call.respondProto(ListFxRatesResponse.newBuilder().addAllFxRates(rates).build())
        }
    }
}
