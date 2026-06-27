package org.tatrman.kantheon.midas.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.PortfolioRepository
import org.tatrman.kantheon.midas.v1.CreatePortfolioRequest
import org.tatrman.kantheon.midas.v1.ListPortfoliosResponse
import org.tatrman.kantheon.midas.v1.PageInfo
import org.tatrman.kantheon.midas.v1.PortfolioResponse
import org.tatrman.kantheon.midas.v1.UpdatePortfolioRequest

/** Portfolios REST surface (contracts §2.2). */
fun Route.portfolioRoutes(
    repo: PortfolioRepository,
    auth: BearerAuthenticator,
) {
    route("/portfolios") {
        post {
            val caller = call.requireCaller(auth) ?: return@post
            val tenant = call.resolveTenant(caller)
            val req = call.receiveProto(CreatePortfolioRequest.newBuilder()).build()
            val created = repo.create(tenant, caller.userId, req.portfolio)
            call.respondProto(PortfolioResponse.newBuilder().setPortfolio(created).build(), HttpStatusCode.Created)
        }

        get {
            val caller = call.requireCaller(auth) ?: return@get
            val tenant = call.resolveTenant(caller)
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
            val (items, total) =
                repo.list(
                    tenant,
                    page,
                    size,
                    call.request.queryParameters["client_id"],
                    call.request.queryParameters["status"],
                )
            call.respondProto(
                ListPortfoliosResponse
                    .newBuilder()
                    .addAllPortfolios(items)
                    .setPageInfo(
                        PageInfo
                            .newBuilder()
                            .setPage(page)
                            .setSize(size)
                            .setTotal(total),
                    ).build(),
            )
        }

        get("/{id}") {
            val caller = call.requireCaller(auth) ?: return@get
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@get
            val p =
                repo.get(tenant, id)
                    ?: throw MidasException.notFound(MidasErrorCode.PORTFOLIO_NOT_FOUND, "portfolio $id not found")
            call.respondProto(PortfolioResponse.newBuilder().setPortfolio(p).build())
        }

        patch("/{id}") {
            val caller = call.requireCaller(auth) ?: return@patch
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@patch
            val req = call.receiveProto(UpdatePortfolioRequest.newBuilder()).build()
            val updated =
                repo.update(tenant, caller.userId, id, req.portfolio)
                    ?: throw MidasException.notFound(MidasErrorCode.PORTFOLIO_NOT_FOUND, "portfolio $id not found")
            call.respondProto(PortfolioResponse.newBuilder().setPortfolio(updated).build())
        }

        post("/{id}/archive") {
            val caller = call.requireCaller(auth) ?: return@post
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@post
            val archived =
                repo.archive(tenant, caller.userId, id)
                    ?: throw MidasException.notFound(MidasErrorCode.PORTFOLIO_NOT_FOUND, "portfolio $id not found")
            call.respondProto(PortfolioResponse.newBuilder().setPortfolio(archived).build())
        }
    }
}
