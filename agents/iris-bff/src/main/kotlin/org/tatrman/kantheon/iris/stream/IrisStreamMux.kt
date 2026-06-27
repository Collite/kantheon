package org.tatrman.kantheon.iris.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.iris.dispatch.golemv2.V2EnvelopeNormalizer
import org.tatrman.kantheon.iris.dispatch.golemv2.V2StreamEvent
import org.tatrman.kantheon.iris.domain.TurnStatus
import org.tatrman.kantheon.iris.v1.DoneEvent
import org.tatrman.kantheon.iris.v1.ErrorEvent
import org.tatrman.kantheon.iris.v1.IrisStreamEvent
import org.tatrman.kantheon.iris.v1.StepEvent

/** What the mux captured about a turn once its v2 stream closed. */
data class TurnOutcome(
    val envelope: FormatEnvelope?,
    val status: TurnStatus,
    val pendingResumeToken: String?,
    val errorCode: String?,
    val doneOutcome: String,
)

/**
 * Maps the new-golem /v2 SSE event stream to `iris/v1.IrisStreamEvent`s
 * (contracts §5), assigning a monotone per-turn `sequence`, normalising the
 * terminal envelope to envelope/v1, and **synthesising the `done` event** on
 * stream close (v2 has no `done`). Returns the [TurnOutcome] for persistence +
 * audit. Pure mapping — the route owns transport (SSE) and persistence.
 */
class IrisStreamMux(
    private val agentId: String = "golem-v2",
) {
    suspend fun run(
        turnId: String,
        events: Flow<V2StreamEvent>,
        emit: suspend (IrisStreamEvent) -> Unit,
    ): TurnOutcome {
        var seq = 0L
        var terminal: FormatEnvelope? = null
        var errorCode: String? = null

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
                    is V2StreamEvent.NodeStart -> emit(step(turnId, ++seq, ev.node, "started", null))
                    is V2StreamEvent.NodeDone -> emit(step(turnId, ++seq, ev.node, "completed", null))
                    is V2StreamEvent.PlanPick ->
                        emit(step(turnId, ++seq, "pick_plan", "completed", planDetail(ev)))
                    is V2StreamEvent.ExecDone ->
                        emit(step(turnId, ++seq, "execute", "completed", execDetail(ev)))
                    is V2StreamEvent.Envelope -> {
                        val env = V2EnvelopeNormalizer.toEnvelope(ev.raw, agentId)
                        terminal = env
                        emit(
                            IrisStreamEvent
                                .newBuilder()
                                .setTurnId(turnId)
                                .setSequence(++seq)
                                .setEnvelope(env)
                                .build(),
                        )
                    }
                    is V2StreamEvent.Error -> {
                        errorCode = ev.code
                        emitError(ev.code, ev.message)
                    }
                }
            }
        } catch (e: CancellationException) {
            // client disconnect / coroutine cancellation — propagate so the call unwinds.
            throw e
        } catch (e: Throwable) {
            // Upstream /v2 stream failed mid-flight. Still emit a terminal error so
            // the wire is well-formed, and fall through to synthesise `done` and
            // return a FAILED outcome so the turn is persisted + audited (C1).
            if (errorCode == null) {
                errorCode = "STREAM_ERROR"
                runCatching { emitError("STREAM_ERROR", e.message ?: "upstream stream error") }
            }
        }

        val outcome = computeOutcome(terminal, errorCode)
        // `done` is best-effort: if the client is already gone the write fails, but
        // the outcome is still returned so persistence/audit run.
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

    private fun computeOutcome(
        terminal: FormatEnvelope?,
        errorCode: String?,
    ): TurnOutcome {
        if (errorCode != null) {
            return TurnOutcome(terminal, TurnStatus.FAILED, null, errorCode, "failed")
        }
        val token =
            terminal
                ?.takeIf { it.hasPendingClarification() }
                ?.pendingClarification
                ?.resumeToken
                ?.takeIf { it.isNotEmpty() }
        val envError = terminal?.errorCode?.takeIf { it.isNotEmpty() }
        return when {
            token != null -> TurnOutcome(terminal, TurnStatus.CLARIFICATION, token, null, "clarification")
            // A terminal envelope carrying an error_code is a finalised failure,
            // not a DONE — keep status/outcome consistent (audit + metrics).
            envError != null -> TurnOutcome(terminal, TurnStatus.FAILED, null, envError, "failed")
            else -> TurnOutcome(terminal, TurnStatus.DONE, null, null, "done")
        }
    }

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

    // Build step detail as real JSON (not string interpolation) so upstream
    // values containing quotes/backslashes can't produce malformed detail_json.
    private fun planDetail(ev: V2StreamEvent.PlanPick): String =
        buildJsonObject {
            put("source", ev.source)
            put("patternId", ev.patternId)
            put("score", ev.score)
        }.toString()

    private fun execDetail(ev: V2StreamEvent.ExecDone): String =
        buildJsonObject {
            put("rowCount", ev.rowCount)
            put("durationMs", ev.durationMs)
        }.toString()
}
