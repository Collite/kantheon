package org.tatrman.kallimachos.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.tatrman.kallimachos.ingestion.DocumentParser
import org.tatrman.kallimachos.model.parseMetadataJson
import org.tatrman.kallimachos.service.ConceptRefInput
import org.tatrman.kallimachos.service.ContextService
import org.tatrman.kallimachos.service.DocumentQueryService
import org.tatrman.kallimachos.service.IngestionService
import org.tatrman.kallimachos.service.LinkLoad
import org.tatrman.kallimachos.service.NotebookNotFoundException
import org.tatrman.kallimachos.service.NotebookService
import org.tatrman.kallimachos.service.PageLoad
import org.tatrman.kallimachos.service.PageLoadService
import org.tatrman.kallimachos.service.QuerySpec

/**
 * The corpus REST surface (P1 Stage 1.2). Search + ingest + notebooks are wired;
 * getContext/findSimilar (P2 graph+vector), browse (P3/P4), and the internal
 * LoadApi write surface (Pinakes, P3) stay stubbed `NOT_IMPLEMENTED`.
 *
 * Mart scope is mandatory: `/documents` and `/query` both require `notebookId`.
 */
private const val FIXTURE_OWNER = "fixture-user" // real OBO principal lands P4

private suspend fun ApplicationCall.notImplemented(surface: String) =
    respond(HttpStatusCode.NotImplemented, mapOf("status" to "NOT_IMPLEMENTED", "surface" to surface))

fun Route.searchRoutes(
    ingestion: IngestionService,
    queries: DocumentQueryService,
    context: ContextService,
    parser: DocumentParser,
) {
    // Ingest — parse → DocNode → parts → one-tx fan-out into the mart.
    post("/documents") {
        val req = call.receive<IngestRequest>()
        if (req.notebookId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto("notebook_id required", "mart scope is mandatory (contracts §7)"),
            )
            return@post
        }
        val metadata = req.metadataJson?.let { parseMetadataJson(it) } ?: emptyMap()
        val root = parser.parse(req.contentText.toByteArray(), req.mimeType)
        try {
            val result =
                ingestion.ingest(
                    notebookId = req.notebookId,
                    root = root,
                    mimeType = req.mimeType,
                    title = req.title,
                    metadata = metadata,
                    assetRef = req.assetRef,
                )
            call.respond(
                HttpStatusCode.Created,
                IngestResponse(
                    sourceId = result.source.id,
                    title = result.source.title,
                    partCount = result.parts.size,
                    partIds = result.parts.map { it.id },
                ),
            )
        } catch (e: NotebookNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("notebook_not_found", e.notebookId))
        }
    }

    post("/query") {
        val req = call.receive<QueryRequest>()
        if (req.notebookId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto("notebook_id required", "no un-scoped search at v1 (contracts §7)"),
            )
            return@post
        }
        val hits =
            queries.query(
                QuerySpec(
                    notebookId = req.notebookId,
                    text = req.text,
                    keywords = req.keywords,
                    metadataFilter = req.metadataJson?.let { parseMetadataJson(it) } ?: emptyMap(),
                    limit = req.limit,
                ),
            )
        call.respond(hits.map { it.toDto() })
    }

    get("/sources/{id}") {
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid id"))
            return@get
        }
        // Mart scoping (defence-in-depth behind the RLS edge): when the caller
        // names a mart, the source must belong to it. A cross-mart id reads as 404.
        val notebookId = call.request.queryParameters["notebookId"]
        if (notebookId != null && !queries.sourceInNotebook(notebookId, id)) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("source_not_found", id.toString()))
            return@get
        }
        val source = queries.getSource(id)
        if (source == null) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("source_not_found", id.toString()))
            return@get
        }
        call.respond(source.toDto(queries.getSourceParts(id)))
    }

    // Graph-primary, citation-bearing RAG (mart scope mandatory).
    post("/getContext") {
        val req = call.receive<ContextRequestDto>()
        if (req.notebookId.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto("notebook_id required", "mart scope is mandatory (contracts §7)"),
            )
            return@post
        }
        val result = context.getContext(req.notebookId, req.query, req.k, req.graphHops, req.vectorBoost)
        call.respond(result.toDto())
    }

    post("/findSimilar") {
        val req = call.receive<FindSimilarRequestDto>()
        if (req.notebookId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("notebook_id required"))
            return@post
        }
        call.respond(context.findSimilar(req.notebookId, req.query, req.k).toDto())
    }
}

fun Route.notebookRoutes(notebooks: NotebookService) {
    route("/notebooks") {
        get {
            call.respond(notebooks.list(FIXTURE_OWNER).map { it.toDto() })
        }
        // The mart ACL (owner + visibility_roles) — the kallimachos-mcp RLS edge
        // fetches this to evaluate the visibility predicate before forwarding.
        get("/{id}") {
            val nb = call.parameters["id"]?.let { notebooks.get(it) }
            if (nb == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("notebook_not_found"))
                return@get
            }
            call.respond(nb.toDto())
        }
        post {
            val req = call.receive<NotebookCreateRequest>()
            val created =
                notebooks.create(
                    displayName = req.displayName,
                    ownerUserId = req.ownerUserId ?: FIXTURE_OWNER,
                    visibilityRoles = req.visibilityRoles,
                    id =
                        req.id ?: java.util.UUID
                            .randomUUID()
                            .toString(),
                )
            call.respond(HttpStatusCode.Created, created.toDto())
        }
    }
}

fun Route.browseRoutes() {
    // P3 (wiki pages) / P4 (MCP edge).
    get("/pages/{id}") { call.notImplemented("browse.getPage") }
    post("/traverse") { call.notImplemented("browse.traverse") }
}

fun Route.loadRoutes(
    ingestion: IngestionService,
    pageLoad: PageLoadService,
) {
    // Internal write surface — Pinakes-only (cluster-internal). `/load/source`
    // (mechanical landing) + `/load/pages` (P3 compile) are live; `/load/vectors`
    // is the EMBED path (P2 admin/embedSource at v1) — stubbed here.
    route("/load") {
        post("/source") {
            val req = call.receive<LoadSourceDto>()
            if (req.notebookId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("notebook_id required"))
                return@post
            }
            try {
                val result =
                    ingestion.ingestParts(
                        notebookId = req.notebookId,
                        partTexts = req.parts,
                        mimeType = req.mimeType,
                        title = req.title,
                        metadata = req.metadataJson?.let { parseMetadataJson(it) } ?: emptyMap(),
                        assetRef = req.assetRef,
                    )
                call.respond(
                    HttpStatusCode.Created,
                    LoadSourceResponse(result.source.id, result.parts.size, result.parts.map { it.id }),
                )
            } catch (e: NotebookNotFoundException) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("notebook_not_found", e.notebookId))
            }
        }
        post("/pages") {
            val req = call.receive<LoadPagesDto>()
            val pageIds =
                pageLoad.loadPages(
                    notebookId = req.notebookId,
                    pages =
                        req.pages.map { p ->
                            PageLoad(
                                localId = p.localId,
                                kind = p.kind,
                                title = p.title,
                                contentMd = p.contentMd,
                                derivedFromParts = p.derivedFromParts,
                                conceptRef =
                                    p.conceptRef?.let {
                                        ConceptRefInput(it.entityType, it.entityId, it.displayLabel, it.ariadneQname)
                                    },
                            )
                        },
                    links = req.links.map { LinkLoad(it.fromLocalId, it.toLocalId, it.edgeKind) },
                )
            call.respond(HttpStatusCode.Created, LoadPagesResponse(pageIds))
        }
        post("/vectors") { call.notImplemented("load.vectors") }
    }
}
