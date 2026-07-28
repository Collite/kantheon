package org.tatrman.kantheon.iris.dispatch.golem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.golem.v1.Caller
import org.tatrman.kantheon.golem.v1.ConversationTurn
import org.tatrman.kantheon.golem.v1.GolemContext
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.iris.dispatch.AgentTurn
import org.tatrman.kantheon.iris.domain.TurnRecord
import org.tatrman.kantheon.iris.domain.TurnStatus

/**
 * Assembles the `GolemRequest` for one dispatched turn (golem contracts §1).
 *
 * Three things travel that the transitional /v2 path had no slot for:
 *
 *  - **`resolved_intent`** — Themis's [org.tatrman.kantheon.themis.v1.Themis.Resolution],
 *    verbatim. Golem trusts Themis upstream (decision 2026-06-12) and reads it for the
 *    composer's function/args bindings, the format enricher, and the escalation chip.
 *    Absent (a KEEP_TOGETHER dispatch, say) it defaults empty and golem composes from the
 *    question text alone — degraded, not broken.
 *  - **`context.handoff`** — the PD-1 handoff the BFF assembles on every dispatch.
 *  - **`context.conversation_excerpt`** — the last few visible turns, BFF-built.
 *
 * `prior_view` is derived from `handoff.view` for the transition (per the golem/v1 comment
 * on the field); native golem reads the handoff directly. It stays unset while the handoff
 * carries no view, which is the current state of [HandoffAssembler][
 * org.tatrman.kantheon.iris.routing.HandoffAssembler] until the PD-4 echo carryover lands.
 *
 * `desired_format` has no counterpart on this surface — golem's format node owns rendering
 * — so it is deliberately dropped rather than smuggled into the question text.
 */
object GolemRequestFactory {
    /** How many prior turns ride in the excerpt. Bounded: the excerpt is inline payload. */
    const val MAX_EXCERPT_TURNS = 5

    private val json = Json { ignoreUnknownKeys = true }

    fun forTurn(
        golemId: String,
        turn: AgentTurn,
        priorTurns: List<TurnRecord> = emptyList(),
    ): GolemRequest {
        val context =
            GolemContext
                .newBuilder()
                .addAllConversationExcerpt(excerpt(priorTurns))
        // Golem selects its prompt bundle by locale (`prompts/<locale>/intent.yaml`), so this
        // field decides the language of the answer's caption and follow-up chips — not just
        // of the SPA's own strings. Left unset when the BFF has no locale, so golem falls
        // back to its Shem's default rather than being told a language nobody chose.
        turn.locale.takeIf { it.isNotBlank() }?.let { context.locale = it }
        turn.handoff?.let { handoff ->
            context.handoff = handoff
            priorView(handoff)?.let { context.priorView = it }
        }
        val builder =
            GolemRequest
                .newBuilder()
                .setId(turn.turnId)
                // The dispatch-map key IS the Shem id Themis routed to, and golem rejects a
                // request whose golem_id doesn't match its loaded Shem — so they are one value.
                .setGolemId(golemId)
                .setQuestion(turn.question)
                .setContext(context)
                .setCaller(
                    Caller
                        .newBuilder()
                        .setUserId(turn.caller.userId)
                        .setTenantId(turn.caller.tenantId)
                        .setCorrelationId(turn.correlationId),
                )
        turn.resolution?.let { builder.resolvedIntent = it }
        return builder.build()
    }

    /**
     * Map the handoff's [ViewProvenance][org.tatrman.kantheon.common.v1.ViewProvenance] onto
     * the envelope-shaped `CurrentView` golem expects. Returns null for an empty view so an
     * ordinary turn is not mislabelled as an AMEND/DRILL with nothing to amend.
     */
    private fun priorView(handoff: org.tatrman.kantheon.common.v1.HandoffContext): CurrentView? {
        if (!handoff.hasView()) return null
        val v = handoff.view
        if (v.bubbleId.isBlank() && v.patternId.isBlank() && v.sql.isBlank()) return null
        val b = CurrentView.newBuilder()
        v.patternId.takeIf { it.isNotBlank() }?.let { b.patternId = it }
        v.argsJson.takeIf { it.isNotBlank() }?.let { b.argsJson = it }
        v.sql.takeIf { it.isNotBlank() }?.let { b.sql = it }
        v.bubbleId.takeIf { it.isNotBlank() }?.let { b.bubbleId = it }
        if (v.totalRows != 0L) b.totalRows = v.totalRows
        return b.build()
    }

    /** The last [MAX_EXCERPT_TURNS] visible turns, oldest→newest. */
    private fun excerpt(turns: List<TurnRecord>): List<ConversationTurn> =
        turns
            .asSequence()
            .filter { it.status != TurnStatus.DISCARDED }
            .sortedBy { it.seq }
            .toList()
            .takeLast(MAX_EXCERPT_TURNS)
            .map { t ->
                val b =
                    ConversationTurn
                        .newBuilder()
                        .setTurnId(t.turnId.toString())
                        .setQuestion(t.question)
                        .setAgentId(t.agentId)
                answerSummary(t.envelopeJson)?.let { b.answerSummary = it }
                b.build()
            }

    /**
     * The persisted envelope's `text` (its caption / first text block) as the excerpt's
     * one-line answer summary. Best-effort: a malformed or absent envelope yields no
     * summary, never a failed dispatch.
     */
    private fun answerSummary(envelopeJson: String?): String? {
        if (envelopeJson.isNullOrBlank()) return null
        return runCatching {
            json
                .parseToJsonElement(envelopeJson)
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
