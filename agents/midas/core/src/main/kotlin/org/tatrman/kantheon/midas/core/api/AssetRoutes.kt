package org.tatrman.kantheon.midas.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.AssetRepository
import org.tatrman.kantheon.midas.v1.AssetResponse
import org.tatrman.kantheon.midas.v1.CreateAssetRequest
import org.tatrman.kantheon.midas.v1.ListAssetsResponse
import org.tatrman.kantheon.midas.v1.PageInfo
import org.tatrman.kantheon.midas.v1.UpdateAssetRequest

/** Assets REST surface (contracts §2.3). */
fun Route.assetRoutes(
    repo: AssetRepository,
    auth: BearerAuthenticator,
) {
    route("/assets") {
        post {
            val caller = call.requireCaller(auth) ?: return@post
            val tenant = call.resolveTenant(caller)
            val req = call.receiveProto(CreateAssetRequest.newBuilder()).build()
            val created = repo.create(tenant, caller.userId, req.asset)
            call.respondProto(AssetResponse.newBuilder().setAsset(created).build(), HttpStatusCode.Created)
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
                    call.request.queryParameters["symbol"],
                    call.request.queryParameters["kind"],
                    call.request.queryParameters["exchange"],
                )
            call.respondProto(
                ListAssetsResponse
                    .newBuilder()
                    .addAllAssets(items)
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
            val a =
                repo.get(tenant, id)
                    ?: throw MidasException.notFound(MidasErrorCode.ASSET_NOT_FOUND, "asset $id not found")
            call.respondProto(AssetResponse.newBuilder().setAsset(a).build())
        }

        patch("/{id}") {
            val caller = call.requireCaller(auth) ?: return@patch
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@patch
            val req = call.receiveProto(UpdateAssetRequest.newBuilder()).build()
            val updated =
                repo.update(tenant, caller.userId, id, req.asset)
                    ?: throw MidasException.notFound(MidasErrorCode.ASSET_NOT_FOUND, "asset $id not found")
            call.respondProto(AssetResponse.newBuilder().setAsset(updated).build())
        }
    }
}
