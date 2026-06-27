package org.tatrman.kantheon.sysifos.bff.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.kantheon.bffbase.auth.BearerAuthenticator
import org.tatrman.kantheon.bffbase.auth.requireCaller
import org.tatrman.kantheon.sysifos.bff.session.SessionStore

/**
 * Session surface (contracts §3.1). Both endpoints are bearer-authenticated; the
 * tenant comes from the JWT claim, never the request body. `POST /sessions`
 * mints a new session; `GET /sessions/current` returns the caller's current one
 * (created on first read).
 */
fun Route.sessionRoutes(
    sessions: SessionStore,
    auth: BearerAuthenticator,
) {
    post("/sessions") {
        val caller = call.requireCaller(auth) ?: return@post
        val session = sessions.create(caller.userId, caller.tenantId)
        call.respond(
            HttpStatusCode.Created,
            buildJsonObject { put("session_id", JsonPrimitive(session.sessionId)) },
        )
    }

    get("/sessions/current") {
        val caller = call.requireCaller(auth) ?: return@get
        call.respondProto(sessions.current(caller.userId, caller.tenantId))
    }
}
