package org.tatrman.kallimachos.retrieval

/** How a chunk surfaced (contracts §1 `RetrievalLead`). */
enum class RetrievalLead { GRAPH, VECTOR, KEYWORD }

/** A citation onto a corpus part (contracts §1 `Citation`; maps to envelope §5). */
data class Citation(
    val sourceId: Long,
    val partId: Long,
    val pageId: Long? = null,
    val title: String,
    val locator: String, // "¶12" / "p.3"
    val sourceRef: String, // "kallimachos://{notebook}/{source}/{part}"
)

/** A retrieved, cited chunk (contracts §1 `ContextChunk`). */
data class RetrievedChunk(
    val partId: Long,
    val sourceId: Long,
    val pageId: Long? = null,
    val text: String,
    val score: Double,
    val lead: RetrievalLead,
    val citation: Citation,
)

/**
 * The result of `getContext`. `grounded = false` is the NO_GROUNDING signal —
 * nothing in-mart cleared `min-score`, so [chunks] is empty and NO fabricated
 * citations are returned (contracts §1 `STATUS_NO_GROUNDING`; Kleio P5 renders a
 * CALLOUT). An empty mart yields the same empty, non-grounded result — never an
 * error.
 */
data class ContextResult(
    val chunks: List<RetrievedChunk>,
    val grounded: Boolean,
)
