package org.tatrman.pinakes.clients

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.tatrman.pinakes.compile.EdgeDraft
import org.tatrman.pinakes.resolve.ResolvedPage

/**
 * The Pinakes → Kallimachos page write path (the LoadApi `LoadPagesRequest`,
 * contracts §1 — the LoadApi is the only corpus writer). Sends compiled pages +
 * page↔page edges; Kallimachos mints page ids, writes the pages, the
 * `DERIVED_FROM` provenance (page → source parts), the content edges, and the
 * mart membership, returning the page ids in draft order.
 */
interface CorpusPageWriter {
    suspend fun writePages(
        notebookId: String,
        sourceId: Long,
        resolved: List<ResolvedPage>,
        edges: List<EdgeDraft>,
    ): List<Long>
}

@Serializable
private data class ConceptRefDto(
    val entityType: String,
    val entityId: String,
    val displayLabel: String,
    val ariadneQname: String = "",
)

@Serializable
private data class PageDto(
    val localId: Int,
    val kind: String,
    val title: String,
    val contentMd: String,
    val derivedFromParts: List<Long>,
    val conceptRef: ConceptRefDto? = null,
)

@Serializable
private data class LinkDto(
    val fromLocalId: Int,
    val toLocalId: Int,
    val edgeKind: String,
)

@Serializable
private data class LoadPagesBody(
    val notebookId: String,
    val sourceId: Long,
    val pages: List<PageDto>,
    val links: List<LinkDto>,
)

@Serializable
private data class LoadPagesResult(
    val pageIds: List<Long> = emptyList(),
)

class HttpCorpusPageWriter(
    private val http: HttpClient,
    private val baseUrl: String,
) : CorpusPageWriter {
    override suspend fun writePages(
        notebookId: String,
        sourceId: Long,
        resolved: List<ResolvedPage>,
        edges: List<EdgeDraft>,
    ): List<Long> {
        if (resolved.isEmpty()) return emptyList()
        val body =
            LoadPagesBody(
                notebookId = notebookId,
                sourceId = sourceId,
                pages =
                    resolved.map { rp ->
                        val d = rp.draft
                        PageDto(
                            localId = d.localId,
                            kind = d.kind.name,
                            title = d.title,
                            contentMd = d.contentMd,
                            derivedFromParts = d.derivedFromParts,
                            conceptRef =
                                d.conceptRef?.let {
                                    ConceptRefDto(it.entityType, it.entityId, it.displayLabel, it.ariadneQname)
                                },
                        )
                    },
                links = edges.map { LinkDto(it.fromLocalId, it.toLocalId, it.kind.name) },
            )
        val resp: HttpResponse =
            http.post("$baseUrl/load/pages") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        require(resp.status.isSuccess()) { "LoadApi /load/pages failed: ${resp.status}" }
        return resp.body<LoadPagesResult>().pageIds
    }
}
