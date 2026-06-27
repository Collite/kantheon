package org.tatrman.kantheon.kleio.graph

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.envelope.v1.Drilldown
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.kleio.clients.GroundedAnswer
import org.tatrman.kantheon.kleio.clients.KallimachosMcpClient
import org.tatrman.kantheon.kleio.clients.KleioLlmClient
import org.tatrman.kantheon.kleio.clients.RetrievedChunk
import org.tatrman.kantheon.kleio.clients.isCitedBy
import org.tatrman.kantheon.kleio.v1.SourceUse

/** Terminal disposition of a Kleio turn (maps to kleio/v1.Status). */
enum class KleioStatus { DONE, NO_GROUNDING, FAILED }

/**
 * Per-turn state threaded through the Kleio node graph (mirrors Golem's
 * GolemTurnState pattern). Immutable — each node returns a copy. This is the
 * Koog-graph-shaped pipeline `Scope → Retrieve → GroundedAnswer → Render`; the
 * literal Koog `AIAgent` DSL is a thin binding over these nodes.
 */
data class KleioTurnState(
    val requestId: String,
    val question: String,
    val notebookId: String,
    val bearer: String?,
    val k: Int,
    val minScore: Double,
    val retrieved: List<RetrievedChunk> = emptyList(),
    val answer: GroundedAnswer? = null,
    val citedChunks: List<RetrievedChunk> = emptyList(),
    val status: KleioStatus = KleioStatus.DONE,
)

data class KleioOutcome(
    val requestId: String,
    val status: KleioStatus,
    val envelope: FormatEnvelope,
    val sourcesUsed: List<SourceUse>,
    val tokensIn: Int,
    val tokensOut: Int,
)

/**
 * The grounded turn. `Scope` binds the mart; `Retrieve` calls `library.getContext`
 * (the retrieved set is the ONLY citable source); `GroundedAnswer` synthesises
 * constrained to the retrieved chunks; `Render` maps citations onto `envelope/v1`
 * and DROPS any model-emitted citation whose part/page id is not in the retrieved
 * set (the grounding contract, contracts §5). Nothing retrieved above `min-score`
 * → `NO_GROUNDING` → an honest CALLOUT refusal, no fabricated citations.
 */
class KleioStrategy(
    private val retriever: KallimachosMcpClient,
    private val llm: KleioLlmClient,
) {
    private val log = LoggerFactory.getLogger(KleioStrategy::class.java)

    suspend fun run(initial: KleioTurnState): KleioOutcome {
        var state = scope(initial)
        if (state.status == KleioStatus.DONE) state = retrieve(state)
        if (state.status == KleioStatus.DONE) state = groundedAnswer(state)
        return render(state)
    }

    private fun scope(state: KleioTurnState): KleioTurnState =
        if (state.notebookId.isBlank()) state.copy(status = KleioStatus.FAILED) else state

    private suspend fun retrieve(state: KleioTurnState): KleioTurnState {
        val chunks = retriever.getContext(state.notebookId, state.question, state.k, state.bearer)
        val above = chunks.filter { it.score >= state.minScore }
        return if (above.isEmpty()) {
            state.copy(retrieved = emptyList(), status = KleioStatus.NO_GROUNDING)
        } else {
            state.copy(retrieved = above)
        }
    }

    private suspend fun groundedAnswer(state: KleioTurnState): KleioTurnState =
        state.copy(answer = llm.answer(state.question, state.retrieved))

    private fun render(state: KleioTurnState): KleioOutcome {
        if (state.status == KleioStatus.NO_GROUNDING) return calloutOutcome(state)
        if (state.status == KleioStatus.FAILED) return failedOutcome(state)

        val answer = state.answer ?: return calloutOutcome(state.copy(status = KleioStatus.NO_GROUNDING))
        // THE GROUNDING CONTRACT: keep only citations pointing at the retrieved set.
        // A page chunk grounds iff its pageId is cited; a part chunk iff its partId
        // is cited. They are matched by disjoint id spaces so a model citing part 0
        // (page chunks carry partId=0) cannot falsely ground every page.
        val cited = state.retrieved.filter { it.isCitedBy(answer) }
        val envelope =
            markdownEnvelope(state.requestId, answer.text)
                .toBuilder()
                .setAgentId(PRODUCING_AGENT)
                .addAllDrilldowns(cited.map { it.toDrilldown(state.notebookId) })
                .build()
        log.info("kleio turn {} grounded: {} cited / {} retrieved", state.requestId, cited.size, state.retrieved.size)
        return KleioOutcome(
            state.requestId,
            KleioStatus.DONE,
            envelope,
            cited.map {
                it.toSourceUse()
            },
            answer.tokensIn,
            answer.tokensOut,
        )
    }

    private fun calloutOutcome(state: KleioTurnState): KleioOutcome {
        val text =
            "I couldn't find anything in this notebook that answers that. " +
                "Rather than guess, I'm flagging it — try rephrasing, or check that the right sources are in the mart."
        val env =
            markdownEnvelope(state.requestId, text)
                .toBuilder()
                .setAgentId(PRODUCING_AGENT)
                .build()
        return KleioOutcome(state.requestId, KleioStatus.NO_GROUNDING, env, emptyList(), 0, 0)
    }

    private fun failedOutcome(state: KleioTurnState): KleioOutcome {
        val env =
            markdownEnvelope(
                state.requestId,
                "Kleio could not process this turn (no notebook bound).",
            ).toBuilder().setAgentId(PRODUCING_AGENT).build()
        return KleioOutcome(state.requestId, KleioStatus.FAILED, env, emptyList(), 0, 0)
    }

    private fun markdownEnvelope(
        turnId: String,
        text: String,
    ): FormatEnvelope =
        FormatEnvelope
            .newBuilder()
            .setTurnId(turnId)
            .setText(text)
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN).build())
            .build()

    private fun RetrievedChunk.toDrilldown(notebookId: String): Drilldown {
        val args =
            buildMap {
                put("notebookId", notebookId)
                put("sourceId", sourceId.toString())
                put("partId", partId.toString())
                pageId?.let { put("pageId", it.toString()) }
            }
        return Drilldown
            .newBuilder()
            // Page and part chunks live in disjoint id spaces — page chunks carry
            // sourceId=partId=0, so they must key on pageId or they all collide on
            // "cite-0-0".
            .setId(if (pageId != null) "cite-page-$pageId" else "cite-$sourceId-$partId")
            .setDisplay("$title — $locator")
            .setScope("point")
            .setSource("citation")
            .putAllArgMapping(args)
            .build()
    }

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

    companion object {
        const val PRODUCING_AGENT = "kleio"
    }
}
