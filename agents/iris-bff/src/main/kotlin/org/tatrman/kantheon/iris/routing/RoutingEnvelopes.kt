package org.tatrman.kantheon.iris.routing

import org.tatrman.kantheon.common.v1.AgentId
import org.tatrman.kantheon.envelope.v1.Chip
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.envelope.v1.PromptChip
import org.tatrman.kantheon.envelope.v1.RoutingPickChip
import org.tatrman.kantheon.themis.v1.Themis.AgentAlternate
import org.tatrman.kantheon.themis.v1.Themis.RefusalWithGaps
import java.time.Instant
import java.util.UUID

/**
 * Builds the BFF-originated, non-dispatch envelopes of the routing layer
 * (Stage 3.1 T5/T6): RoutingPickChips on `needs_user_pick`, decomposition
 * PromptChips on a SPLIT multi-question, the RefusalWithGaps error envelope, and
 * plaintext hint bubbles. These never call an agent — one envelope, then `done`.
 */
class RoutingEnvelopes(
    private val labels: AgentLabels,
    private val routerAgentId: String = "themis",
    private val now: () -> Instant = Instant::now,
) {
    /** Layer-3 ambiguity: one RoutingPickChip per runner-up (label from capabilities). */
    suspend fun routingPick(
        turnId: String,
        threadId: UUID,
        alternates: List<AgentAlternate>,
    ): FormatEnvelope {
        val builder = base(turnId, threadId, "Several agents can answer this — which one?")
        for (alt in alternates) {
            val id = alt.agentId.value
            builder.addChips(
                Chip
                    .newBuilder()
                    .setRouting(
                        RoutingPickChip
                            .newBuilder()
                            .setAgentId(AgentId.newBuilder().setValue(id))
                            .setLabel(labels.displayName(id))
                            .setWhy(alt.why),
                    ),
            )
        }
        return builder.build()
    }

    /** PD-13 SPLIT: one PromptChip per self-standing sub-question; click re-submits it. */
    fun decomposition(
        turnId: String,
        threadId: UUID,
        subQuestions: List<String>,
    ): FormatEnvelope {
        val builder = base(turnId, threadId, "This looks like more than one question — pick one to start:")
        for (sub in subQuestions) {
            builder.addChips(
                Chip
                    .newBuilder()
                    .setPrompt(
                        PromptChip
                            .newBuilder()
                            .setDisplay(sub)
                            .setPrompt(sub)
                            .setSource("static"),
                    ),
            )
        }
        return builder.build()
    }

    /**
     * RefusalWithGaps → error envelope. `error_code` = the first gap kind (esp.
     * `NO_ENTITLED_AGENT`, reveal-existence-deny-access); the body lists each gap
     * description + its suggested action.
     */
    fun refusal(
        turnId: String,
        threadId: UUID,
        refusal: RefusalWithGaps,
    ): FormatEnvelope {
        val primary =
            refusal.gapsList
                .firstOrNull()
                ?.kind
                ?.name ?: "REFUSED"
        val lines =
            buildList {
                if (refusal.rationale.isNotBlank()) add(refusal.rationale)
                for (gap in refusal.gapsList) {
                    val action = if (gap.hasSuggestedAction()) " — ${gap.suggestedAction}" else ""
                    add("• ${gap.description}$action")
                }
            }
        return base(turnId, threadId, lines.joinToString("\n"))
            .setErrorCode(primary)
            .build()
    }

    /**
     * A plain Themis clarification (entity/intent ambiguity): the question as
     * text + one PromptChip per option label; clicking re-submits as a fresh turn.
     */
    fun clarification(
        turnId: String,
        threadId: UUID,
        question: String,
        options: List<String>,
    ): FormatEnvelope {
        val builder = base(turnId, threadId, question)
        for (option in options) {
            builder.addChips(
                Chip
                    .newBuilder()
                    .setPrompt(
                        PromptChip
                            .newBuilder()
                            .setDisplay(option)
                            .setPrompt(option)
                            .setSource("static"),
                    ),
            )
        }
        return builder.build()
    }

    /** A plaintext hint bubble (e.g. KEEP_TOGETHER decomposition rationale). */
    fun hint(
        turnId: String,
        threadId: UUID,
        text: String,
    ): FormatEnvelope = base(turnId, threadId, text).build()

    /** Generic error envelope (Themis unreachable, no routing decision, …). */
    fun error(
        turnId: String,
        threadId: UUID,
        code: String,
        message: String,
    ): FormatEnvelope = base(turnId, threadId, message).setErrorCode(code).build()

    private fun base(
        turnId: String,
        threadId: UUID,
        text: String,
    ): FormatEnvelope.Builder =
        FormatEnvelope
            .newBuilder()
            .setBubbleId(UUID.randomUUID().toString())
            .setTurnId(turnId)
            .setThreadId(threadId.toString())
            .setText(text)
            .setFormat(FormatSpec.newBuilder().setKind(FormatKind.PLAINTEXT))
            .setAgentId(routerAgentId)
            .setAgentVersion("iris-bff@router")
            .setCreatedAt(now().toString())
}
