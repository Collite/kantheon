package org.tatrman.kallimachos.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.tatrman.kallimachos.service.EmbeddingService

/**
 * Ops/admin surface. `/admin/backfillEmbeddings` runs the EMBED backfill over
 * PENDING sources; `/admin/embedSource/{id}` embeds one loaded source (the
 * Pinakes EMBED stage calls this). Both are the non-atomic embedding edge. v1
 * admin-gated at the MCP edge (P4); here cluster-internal ops.
 */
fun Route.adminRoutes(embeddingService: EmbeddingService) {
    post("/admin/backfillEmbeddings") {
        val embedded = embeddingService.backfillEmbeddings()
        call.respond(mapOf("embedded" to embedded))
    }
    post("/admin/embedSource/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid id"))
            return@post
        }
        val ok = embeddingService.embedSource(id)
        call.respond(mapOf("sourceId" to id, "embedded" to ok))
    }
}
