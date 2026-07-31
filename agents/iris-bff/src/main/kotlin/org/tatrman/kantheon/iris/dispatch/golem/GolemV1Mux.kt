package org.tatrman.kantheon.iris.dispatch.golem

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.envelope.v1.CurrentView
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.golem.v1.ConversationalResponse
import org.tatrman.kantheon.golem.v1.Status
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.stream.TurnOutcome
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.iris.v1.StepEvent
import java.util.UUID

/**
 * Maps the native Golem SSE stream to `iris/v1.IrisStreamEvent`s and the [TurnOutcome]
 * the dispatcher persists + audits. Pure mapping — transport belongs to the client, SSE
 * to the route.
 *
 * Where this differs from the transitional /v2 mux, and why:
 *
 *  - **One turn, *n* bubbles.** Golem's terminal frame is a `ConversationalResponse` with
 *    a repeated `envelopes` field (one per render node), so the mux emits one envelope
 *    event per element instead of assuming a single terminal envelope.
 *  - **Status is stated, not inferred.** /v2 had to guess a turn's fate from the shape of
 *    the envelope; `ConversationalResponse.status` says it outright. That matters here:
 *    golem's param-fill clarification envelope *also* carries an `error_code`, which the
 *    inferring rule would have read as a failed turn.
 *  - **Rule-6 messages are turn-level.** They have no bubble of their own, so they ride on
 *    the first envelope — the only place the FE can render them.
 *
 * There is no `done` on golem's wire; the mux synthesises one at stream close, as /v2 did.
 */
class GolemV1Mux {
    @Suppress("LongMethod")
    suspend fun run(
        turnId: String,
        sessionId: UUID,
        agentId: String,
        events: Flow<GolemV1Event>,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        var seq = 0L
        var response: ConversationalResponse? = null
        var errorCode: String? = null
        val emitted = mutableListOf<FormatEnvelope>()

        suspend fun emitError(
            code: String,
            message: String,
        ) {
            emit(
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId(turnId)
                    .setSequence(++seq)
                    .setError(
                        ErrorEvent
                            .newBuilder()
                            .setCode(code)
                            .setMessage(message)
                            .setRecoverable(false),
                    ).build(),
            )
        }

        try {
            events.collect { ev ->
                when (ev) {
                    is GolemV1Event.NodeStart -> emit(step(turnId, ++seq, ev.node, "started", null))
                    is GolemV1Event.NodeDone -> emit(step(turnId, ++seq, ev.node, "completed", null))
                    is GolemV1Event.PlanPick ->
                        emit(step(turnId, ++seq, "pick_plan", "completed", planDetail(ev)))
                    is GolemV1Event.ExecDone ->
                        emit(step(turnId, ++seq, "execute", "completed", execDetail(ev)))
                    is GolemV1Event.Turn -> {
                        response = ev.response
                        enrich(ev.response, turnId, sessionId, agentId).forEach { env ->
                            emitted += env
                            emit(
                                IrisStreamEvent
                                    .newBuilder()
                                    .setTurnId(turnId)
                                    .setSequence(++seq)
                                    .setEnvelope(env)
                                    .build(),
                            )
                        }
                    }
                    is GolemV1Event.Error -> {
                        errorCode = ev.code
                        emitError(ev.code, ev.message)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Client disconnect / coroutine cancellation — propagate so the call unwinds.
            throw e
        } catch (e: Throwable) {
            // The upstream stream failed mid-flight. Still emit a terminal error so the wire
            // is well-formed, then fall through to synthesise `done` and return a FAILED
            // outcome, so the turn is persisted + audited rather than vanishing.
            if (errorCode == null) {
                errorCode = STREAM_ERROR
                runCatching { emitError(STREAM_ERROR, e.message ?: "upstream stream error") }
            }
        }

        // A stream that closed with no terminal frame at all is a failure, not an empty
        // answer — the user must not be shown a silent blank bubble.
        if (response == null && errorCode == null) {
            errorCode = NO_TERMINAL_FRAME
            runCatching { emitError(NO_TERMINAL_FRAME, "golem closed the stream without an answer") }
        }

        val outcome = computeOutcome(response, emitted, errorCode)
        // `done` is best-effort: if the client is already gone the write fails, but the
        // outcome is still returned so persistence/audit run.
        runCatching {
            emit(
                IrisStreamEvent
                    .newBuilder()
                    .setTurnId(turnId)
                    .setSequence(++seq)
                    .setDone(DoneEvent.newBuilder().setOutcome(outcome.doneOutcome))
                    .build(),
            )
        }
        return outcome
    }

    /**
     * BFF enrichment of golem's envelopes (the fields golem cannot know or the contract
     * marks BFF-owned): the Iris `thread_id`, the producing `agent_id`, the clarification's
     * `issued_by_agent_id` (iris/v1 resume-routing), and the turn's `current_view` stamped
     * onto the bubble it actually anchors. Anything golem already set is left alone.
     */
    private fun enrich(
        response: ConversationalResponse,
        turnId: String,
        sessionId: UUID,
        agentId: String,
    ): List<FormatEnvelope> {
        val anchorBubble = response.currentView.bubbleId.takeIf { response.hasCurrentView() && it.isNotBlank() }
        return response.envelopesList.mapIndexed { index, env ->
            val b = env.toBuilder()
            if (b.turnId.isBlank()) b.turnId = turnId
            if (b.threadId.isBlank()) b.threadId = sessionId.toString()
            if (!b.hasAgentId() || b.agentId.isBlank()) b.agentId = agentId
            if (b.hasPendingClarification() && b.pendingClarification.issuedByAgentId.isBlank()) {
                b.pendingClarificationBuilder.issuedByAgentId = agentId
            }
            if (!b.hasCurrentView() && anchorBubble != null && anchorBubble == env.bubbleId) {
                b.currentView = currentView(response)
            }
            // Turn-level Rule-6 messages have no bubble of their own — surface them on the
            // first one, which is the only place the FE renders them.
            if (index == 0) b.addAllMessages(response.messagesList)
            b.build()
        }
    }

    private fun currentView(response: ConversationalResponse): CurrentView {
        val v = response.currentView
        val b = CurrentView.newBuilder()
        v.patternId.takeIf { it.isNotBlank() }?.let { b.patternId = it }
        v.argsJson.takeIf { it.isNotBlank() }?.let { b.argsJson = it }
        v.sql.takeIf { it.isNotBlank() }?.let { b.sql = it }
        v.bubbleId.takeIf { it.isNotBlank() }?.let { b.bubbleId = it }
        if (v.totalRows != 0L) b.totalRows = v.totalRows
        return b.build()
    }

    /**
     * `TurnOutcome` carries one envelope (it is what persists as the turn's
     * `envelope_json` + `displayed_block_ids`). For a multi-bubble turn that is the **last**
     * envelope — the answer block, and the one golem's `current_view` anchors — except when
     * a bubble is asking for clarification, which always wins because it owns the resume
     * token the next turn routes on.
     */
    private fun computeOutcome(
        response: ConversationalResponse?,
        envelopes: List<FormatEnvelope>,
        errorCode: String?,
    ): TurnOutcome {
        val clarification = envelopes.firstOrNull { it.hasPendingClarification() }
        val representative = clarification ?: envelopes.lastOrNull()

        // PT-25/S-5: carry the agent's hints on EVERY outcome, including failures —
        // a turn that failed mid-pipeline is exactly the one whose plan ids and
        // timings a protocol reader wants. `hasProtocolHints()` distinguishes
        // "agent sent an empty block" from "agent sent none", which the recorder
        // must not conflate.
        val hints = response?.takeIf { it.hasProtocolHints() }?.protocolHints

        if (errorCode != null) {
            return TurnOutcome(representative, TurnStatus.FAILED, null, errorCode, "failed", hints)
        }
        val resumeToken =
            clarification
                ?.pendingClarification
                ?.resumeToken
                ?.takeIf { it.isNotEmpty() }
        return when (response?.status) {
            Status.STATUS_CLARIFICATION ->
                // A clarification with no resume token cannot be answered — the FE would
                // render a dead-end prompt. Report it as a failure, not a pending question.
                if (resumeToken != null) {
                    TurnOutcome(representative, TurnStatus.CLARIFICATION, resumeToken, null, "clarification", hints)
                } else {
                    TurnOutcome(representative, TurnStatus.FAILED, null, "GOLEM_NO_RESUME_TOKEN", "failed", hints)
                }
            Status.STATUS_FAILED ->
                TurnOutcome(representative, TurnStatus.FAILED, null, failureCode(response, envelopes), "failed", hints)
            else -> TurnOutcome(representative, TurnStatus.DONE, null, null, "done", hints)
        }
    }

    /** The most specific code a failed turn offers: envelope, then Rule-6, then a default. */
    private fun failureCode(
        response: ConversationalResponse,
        envelopes: List<FormatEnvelope>,
    ): String =
        envelopes.firstNotNullOfOrNull { it.errorCode.takeIf { c -> c.isNotBlank() } }
            ?: response.messagesList
                .firstOrNull { it.severity == Severity.ERROR }
                ?.code
                ?.takeIf { it.isNotBlank() }
            ?: "GOLEM_TURN_FAILED"

    private fun step(
        turnId: String,
        seq: Long,
        node: String,
        phase: String,
        detailJson: String?,
    ): IrisStreamEvent {
        val stepBuilder = StepEvent.newBuilder().setNode(node).setPhase(phase)
        if (detailJson != null) stepBuilder.detailJson = detailJson
        return IrisStreamEvent
            .newBuilder()
            .setTurnId(turnId)
            .setSequence(seq)
            .setStep(stepBuilder)
            .build()
    }

    // Step detail is built as real JSON (not interpolation) so upstream values containing
    // quotes/backslashes can't produce a malformed detail_json.
    private fun planDetail(ev: GolemV1Event.PlanPick): String =
        buildJsonObject {
            put("source", ev.source)
            put("score", ev.score)
        }.toString()

    private fun execDetail(ev: GolemV1Event.ExecDone): String =
        buildJsonObject {
            put("rowCount", ev.rowCount)
            put("durationMs", ev.durationMs)
        }.toString()

    companion object {
        private const val STREAM_ERROR = "GOLEM_STREAM_ERROR"
        private const val NO_TERMINAL_FRAME = "GOLEM_NO_TERMINAL_FRAME"
    }
}
