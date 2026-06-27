package org.tatrman.kallimachos.retrieval

import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kantheon.common.v1.BlockProvenance
import org.tatrman.kantheon.envelope.v1.Drilldown

/**
 * Builds a [Citation] for a corpus part and maps it onto `envelope/v1` — the
 * grounding contract (contracts §5) that Kleio (P5) renders cited blocks
 * against. `RenderNode` drops any model-emitted citation whose ids aren't in the
 * retrieved set; absence renders "provenance unavailable", never an error (PD-9).
 */
object Citations {
    const val PRODUCING_AGENT = "kleio"

    fun citationFor(
        part: PartRecord,
        source: SourceRecord,
        notebookId: String,
    ): Citation =
        Citation(
            sourceId = source.id,
            partId = part.id,
            pageId = null, // pages join in P3
            title = source.title,
            locator = "¶${part.idx}",
            sourceRef = "kallimachos://$notebookId/${source.id}/${part.id}",
        )

    /** Citation onto a compiled wiki page (S3.2 — pages lead retrieval). */
    fun citationForPage(
        pageId: Long,
        title: String,
        notebookId: String,
    ): Citation =
        Citation(
            sourceId = 0,
            partId = 0,
            pageId = pageId,
            title = title,
            locator = "page",
            sourceRef = "kallimachos://$notebookId/page/$pageId",
        )

    /** `source_ref` → `Block.provenance.source_tables[]`; agent + computed_at. */
    fun toProvenance(
        citation: Citation,
        computedAt: String = "",
    ): BlockProvenance =
        BlockProvenance
            .newBuilder()
            .setProducingAgentId(PRODUCING_AGENT)
            .addSourceTables(citation.sourceRef)
            .setComputedAt(computedAt)
            .build()

    /** ids → `Drilldown.arg_mapping`; `scope="point"`, `source="citation"`. */
    fun toDrilldown(citation: Citation): Drilldown {
        val args =
            buildMap {
                put("sourceId", citation.sourceId.toString())
                put("partId", citation.partId.toString())
                citation.pageId?.let { put("pageId", it.toString()) }
            }
        return Drilldown
            .newBuilder()
            .setId("cite-${citation.sourceId}-${citation.partId}")
            .setDisplay("${citation.title} — ${citation.locator}")
            .setScope("point")
            .setSource("citation")
            .putAllArgMapping(args)
            .build()
    }
}
