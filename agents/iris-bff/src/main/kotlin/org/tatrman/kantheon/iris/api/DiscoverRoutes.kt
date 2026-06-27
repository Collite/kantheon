package org.tatrman.kantheon.iris.api

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesReadClient
import org.tatrman.kantheon.iris.discover.DiscoverService

private val log = LoggerFactory.getLogger("org.tatrman.kantheon.iris.api.DiscoverRoutes")

@Serializable
data class DomainCardDto(
    val agentId: String,
    val displayName: String,
    val blurb: String,
    val exampleQuestions: List<String>,
)

@Serializable
data class DiscoverResponseDto(
    val domains: List<DomainCardDto>,
)

/**
 * Discovery surface (PD-7, contracts §2.6): `GET /v1/discover` → role-filtered
 * DomainCards assembled from the capabilities-mcp agent manifests (`non_routable`
 * excluded; an agent's `visibility_roles` gate it to the caller's bearer roles).
 * Capabilities-mcp down → an empty list (best-effort; the FE shows nothing rather
 * than failing the session).
 */
fun Route.discoverRoutes(
    capabilities: CapabilitiesReadClient,
    auth: BearerAuthenticator,
) {
    get("/v1/discover") {
        val caller = call.requireCaller(auth) ?: return@get
        val roles = BearerRoles.rolesOf(caller.bearer)
        val cards =
            try {
                DiscoverService.build(capabilities.listAgents(), roles)
            } catch (e: Throwable) {
                log.warn("discover: capabilities-mcp unavailable — empty surface", e)
                emptyList()
            }
        call.respond(
            DiscoverResponseDto(
                cards.map { DomainCardDto(it.agentId, it.displayName, it.blurb, it.exampleQuestions) },
            ),
        )
    }
}
