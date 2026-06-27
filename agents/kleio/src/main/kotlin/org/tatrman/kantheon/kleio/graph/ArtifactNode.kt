package org.tatrman.kantheon.kleio.graph

import org.tatrman.kantheon.envelope.v1.Drilldown
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.kleio.clients.KallimachosMcpClient
import org.tatrman.kantheon.kleio.clients.KleioLlmClient
import org.tatrman.kantheon.kleio.clients.RetrievedChunk
import org.tatrman.kantheon.kleio.clients.isCitedBy
import org.tatrman.kantheon.kleio.v1.ArtifactKind
import org.tatrman.kantheon.kleio.v1.SourceUse
import java.util.UUID

data class ArtifactOutcome(
    val artifactId: String,
    val kind: ArtifactKind,
    val envelope: FormatEnvelope,
    val sourcesUsed: List<SourceUse>,
    val grounded: Boolean,
)

/**
 * Artifact generation (Stage 5.2): SUMMARY / FAQ / TIMELINE / BRIEFING as a
 * map-reduce over a mart — retrieve broadly (`library.getContext`), synthesise
 * the artifact, and apply the SAME grounding contract as a turn (cite only
 * retrieved nodes). An empty mart yields a "not enough sources" artifact, never a
 * fabricated one.
 */
class ArtifactNode(
    private val retriever: KallimachosMcpClient,
    private val llm: KleioLlmClient,
    private val k: Int = 24,
) {
    suspend fun generate(
        notebookId: String,
        kind: ArtifactKind,
        focus: String?,
        bearer: String?,
    ): ArtifactOutcome {
        val id = UUID.randomUUID().toString()
        val query = queryFor(kind, focus)
        val chunks = retriever.getContext(notebookId, query, k, bearer)
        if (chunks.isEmpty()) {
            return ArtifactOutcome(
                id,
                kind,
                markdown("Not enough sources in this notebook to build a ${kind.name.lowercase()}."),
                emptyList(),
                grounded = false,
            )
        }
        val answer = llm.answer(query, chunks)
        // Grounding contract: page chunks ground on pageId, part chunks on partId
        // (disjoint id spaces — see RetrievedChunk.isCitedBy).
        val cited = chunks.filter { it.isCitedBy(answer) }
        val envelope =
            markdown(answer.text)
                .toBuilder()
                .setAgentId("kleio")
                .addAllDrilldowns(
                    cited.map {
                        it.toDrilldown(notebookId)
                    },
                ).build()
        return ArtifactOutcome(id, kind, envelope, cited.map { it.toSourceUse() }, grounded = cited.isNotEmpty())
    }

    private fun queryFor(
        kind: ArtifactKind,
        focus: String?,
    ): String {
        val f = focus?.takeIf { it.isNotBlank() }?.let { " focused on $it" } ?: ""
        return when (kind) {
            ArtifactKind.SUMMARY -> "Summarise this notebook$f."
            ArtifactKind.FAQ -> "Generate an FAQ from this notebook$f."
            ArtifactKind.TIMELINE -> "Build a chronological timeline from this notebook$f."
            ArtifactKind.BRIEFING -> "Write an executive briefing on this notebook$f."
            else -> "Summarise this notebook$f."
        }
    }

    private fun markdown(text: String): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .setText(
                text,
            ).setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN).build())
            .build()

    private fun RetrievedChunk.toDrilldown(notebookId: String): Drilldown =
        Drilldown
            .newBuilder()
            // Disjoint id spaces — page chunks carry sourceId=partId=0, so they key
            // on pageId or collide on "cite-0-0".
            .setId(if (pageId != null) "cite-page-$pageId" else "cite-$sourceId-$partId")
            .setDisplay("$title — $locator")
            .setScope("point")
            .setSource("citation")
            .putAllArgMapping(
                buildMap {
                    put("notebookId", notebookId)
                    put("sourceId", sourceId.toString())
                    put("partId", partId.toString())
                    pageId?.let { put("pageId", it.toString()) }
                },
            ).build()

    private fun RetrievedChunk.toSourceUse(): SourceUse {
        val b =
            SourceUse
                .newBuilder()
                .setSourceId(sourceId)
                .setPartId(partId)
                .setTitle(title)
                .setScore(score)
        pageId?.let { b.pageId = it }
        return b.build()
    }
}
