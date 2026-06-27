package org.tatrman.kantheon.capabilities.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.kantheon.capabilities.registry.RegistryQueryService
import org.tatrman.kantheon.capabilities.v1.CapabilityFilter
import org.tatrman.kantheon.capabilities.v1.IntentKind
import org.tatrman.kantheon.common.v1.Severity

fun Route.capabilitiesRestRoutes(service: RegistryQueryService) {
    route("/v1/capabilities") {
        post("/search") {
            val req = call.receive<JsonObject>()
            val params =
                RegistryQueryService.SearchParams(
                    intentKinds = req.stringList("intentKinds").map(IntentKind::valueOf),
                    entityTypes = req.stringList("entityTypes"),
                    capabilityTags = req.stringList("capabilityTags"),
                    filter = req["filter"]?.let { it as? JsonObject }?.toCapabilityFilter(),
                )
            val entries = service.search(params)
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("entries", JsonArray(entries.map { CapabilityJson.capabilityToJson(it) }))
                    put("messages", CapabilityJson.emptyMessages())
                },
            )
        }

        get {
            val params =
                RegistryQueryService.ListParams(
                    category = call.request.queryParameters["category"],
                    filter = call.queryFilter(),
                )
            val entries = service.list(params)
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("entries", JsonArray(entries.map { CapabilityJson.capabilityToJson(it) }))
                    put("messages", CapabilityJson.emptyMessages())
                },
            )
        }

        get("/agents") {
            val agents = service.listAgents(filter = call.queryFilter())
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("agents", JsonArray(agents.map { CapabilityJson.agentToJson(it) }))
                    put("messages", CapabilityJson.emptyMessages())
                },
            )
        }

        post("/register") {
            val req = call.receive<JsonObject>()
            val capability =
                CapabilityJson.capabilityFromJson(
                    req["capability"]?.let { it as? JsonObject }
                        ?: return@post call.respondError(
                            status = HttpStatusCode.BadRequest,
                            code = "missing_capability",
                            message = "register requires a 'capability' object",
                        ),
                )
            val rid = service.register(capability)
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("registrationId", JsonPrimitive(rid))
                    put("messages", CapabilityJson.emptyMessages())
                },
            )
        }

        post("/{registrationId}/heartbeat") {
            val rid = call.parameters["registrationId"].orEmpty()
            when (val outcome = service.heartbeat(rid)) {
                is RegistryQueryService.HeartbeatOutcome.Accepted ->
                    call.respond(
                        HttpStatusCode.OK,
                        buildJsonObject {
                            put("acceptedAt", JsonPrimitive(outcome.acceptedAt.toString()))
                            put("messages", CapabilityJson.emptyMessages())
                        },
                    )

                RegistryQueryService.HeartbeatOutcome.Unknown ->
                    call.respondError(
                        status = HttpStatusCode.NotFound,
                        code = "unknown_registration_id",
                        message = "registration_id '$rid' is not registered",
                    )
            }
        }

        // {id} must be registered AFTER the more specific /agents and /{registrationId}/heartbeat
        // routes; Ktor matches in registration order.
        get("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val cap = service.get(id)
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    if (cap == null) {
                        put("capability", JsonNull)
                    } else {
                        put("capability", CapabilityJson.capabilityToJson(cap))
                    }
                    put("messages", CapabilityJson.emptyMessages())
                },
            )
        }
    }
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(
        status,
        buildJsonObject {
            put(
                "messages",
                buildJsonArray { add(CapabilityJson.messageJson(Severity.ERROR, code, message)) },
            )
        },
    )
}

private fun ApplicationCall.queryFilter(): CapabilityFilter? {
    val params = request.queryParameters
    if (params["filter.includeTools"] == null &&
        params["filter.includeAgents"] == null &&
        params["filter.includePruned"] == null
    ) {
        return null
    }
    val b = CapabilityFilter.newBuilder()
    params["filter.includeTools"]?.toBooleanStrictOrNull()?.let { b.includeTools = it }
    params["filter.includeAgents"]?.toBooleanStrictOrNull()?.let { b.includeAgents = it }
    params["filter.includePruned"]?.toBooleanStrictOrNull()?.let { b.includePruned = it }
    return b.build()
}

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

private fun JsonObject.toCapabilityFilter(): CapabilityFilter {
    val b = CapabilityFilter.newBuilder()
    (this["includeTools"] as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()?.let { b.includeTools = it }
    (this["includeAgents"] as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()?.let { b.includeAgents = it }
    (this["includePruned"] as? JsonPrimitive)?.contentOrNull()?.toBooleanStrictOrNull()?.let { b.includePruned = it }
    (this["includeTools"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
        ?.let { b.includeTools = it }
    (this["includeAgents"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
        ?.let { b.includeAgents = it }
    (this["includePruned"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
        ?.let { b.includePruned = it }
    return b.build()
}

private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else content
