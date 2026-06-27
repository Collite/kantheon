package org.tatrman.kantheon.midas.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.ClientRepository
import org.tatrman.kantheon.midas.v1.ClientResponse
import org.tatrman.kantheon.midas.v1.CreateClientRequest
import org.tatrman.kantheon.midas.v1.ListClientsResponse
import org.tatrman.kantheon.midas.v1.PageInfo
import org.tatrman.kantheon.midas.v1.UpdateClientRequest

/** Clients REST surface (contracts §2.1). */
fun Route.clientRoutes(
    repo: ClientRepository,
    auth: BearerAuthenticator,
) {
    route("/clients") {
        post {
            val caller = call.requireCaller(auth) ?: return@post
            val tenant = call.resolveTenant(caller)
            val req = call.receiveProto(CreateClientRequest.newBuilder()).build()
            val created = repo.create(tenant, caller.userId, req.client)
            call.respondProto(ClientResponse.newBuilder().setClient(created).build(), HttpStatusCode.Created)
        }

        get {
            val caller = call.requireCaller(auth) ?: return@get
            val tenant = call.resolveTenant(caller)
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
            val status = call.request.queryParameters["status"]
            val namePrefix = call.request.queryParameters["name_prefix"]
            val (clients, total) = repo.list(tenant, page, size, status, namePrefix)
            call.respondProto(
                ListClientsResponse
                    .newBuilder()
                    .addAllClients(clients)
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
            val client =
                repo.get(tenant, id)
                    ?: throw MidasException.notFound(MidasErrorCode.CLIENT_NOT_FOUND, "client $id not found")
            call.respondProto(ClientResponse.newBuilder().setClient(client).build())
        }

        patch("/{id}") {
            val caller = call.requireCaller(auth) ?: return@patch
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@patch
            val req = call.receiveProto(UpdateClientRequest.newBuilder()).build()
            val updated =
                repo.update(tenant, caller.userId, id, req.client)
                    ?: throw MidasException.notFound(MidasErrorCode.CLIENT_NOT_FOUND, "client $id not found")
            call.respondProto(ClientResponse.newBuilder().setClient(updated).build())
        }

        post("/{id}/archive") {
            val caller = call.requireCaller(auth) ?: return@post
            val tenant = call.resolveTenant(caller)
            val id = pathUuid("id") ?: return@post
            val archived =
                repo.archive(tenant, caller.userId, id)
                    ?: throw MidasException.notFound(MidasErrorCode.CLIENT_NOT_FOUND, "client $id not found")
            call.respondProto(ClientResponse.newBuilder().setClient(archived).build())
        }
    }
}
