package org.tatrman.kantheon.iris.domain

/** One prior turn in the conversation excerpt (transitionally informational). */
data class ExcerptTurn(
    val question: String,
    val agentId: String,
    val status: String,
)

data class ConversationExcerpt(
    val turns: List<ExcerptTurn>,
)

/**
 * Builds the last-N-turns excerpt from `iris_turns` (contracts §5 / plan 1.3 T5).
 * Transitionally informational — new-golem keeps its own thread state; the
 * excerpt becomes load-bearing once native agents consume it (Phase 3). Visible
 * (non-discarded) turns only, oldest→newest.
 */
object ConversationExcerptBuilder {
    fun build(
        turns: List<TurnRecord>,
        maxTurns: Int,
    ): ConversationExcerpt =
        ConversationExcerpt(
            turns
                .asSequence()
                .filter { it.status != TurnStatus.DISCARDED }
                .sortedBy { it.seq }
                .toList()
                .takeLast(maxTurns)
                .map { ExcerptTurn(it.question, it.agentId, it.status.wire) },
        )
}
