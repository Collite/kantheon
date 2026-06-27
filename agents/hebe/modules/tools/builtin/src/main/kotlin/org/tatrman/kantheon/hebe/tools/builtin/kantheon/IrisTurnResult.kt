package org.tatrman.kantheon.hebe.tools.builtin.kantheon

import org.tatrman.kantheon.envelope.v1.FormatEnvelope
import org.tatrman.kantheon.hebe.v1.RunStatus
import org.tatrman.kantheon.iris.v1.IrisStreamEvent

/**
 * The resolution of an iris-bff turn as seen by Hebe's headless client (P4 S4.1 T5).
 * Distinct from the persisted `RunStatus`: the client reports the *turn* outcome;
 * delivery (and the move to `DELIVERED`) is Stage 4.2. `clarification` / agent pause
 * → [AwaitingAgent] (Hebe does not answer clarifications in v1, contracts §3.4); a
 * stream error/timeout or a missing terminal → [Failed] (never a silent hang).
 */
sealed interface IrisTurnResult {
    val turnRef: String?

    data class Succeeded(
        override val turnRef: String?,
        val envelope: FormatEnvelope?,
    ) : IrisTurnResult

    data class AwaitingAgent(
        override val turnRef: String?,
    ) : IrisTurnResult

    data class Failed(
        override val turnRef: String?,
        val error: String,
    ) : IrisTurnResult

    /**
     * Maps to the persisted [RunStatus]. A succeeded turn is `DELIVERED` only once
     * Stage 4.2 has delivered it ([delivered]); before that it is still `RUNNING`.
     */
    fun runStatus(delivered: Boolean): RunStatus =
        when (this) {
            is Succeeded -> if (delivered) RunStatus.DELIVERED else RunStatus.RUNNING
            is AwaitingAgent -> RunStatus.AWAITING_AGENT
            is Failed -> RunStatus.FAILED
        }
}

/**
 * Pure mapping of a consumed SSE event sequence to an [IrisTurnResult] (exhaustive
 * over the [IrisStreamEvent] event types). `DoneEvent.outcome` is the terminal
 * signal: `done` → succeeded (last envelope attached), `failed` → failed,
 * `clarification` → awaiting-agent. An `ErrorEvent` fails. A stream that ends with
 * no terminal `done`/`error` is a failure (the patient client still never hangs).
 */
object IrisStreamMapping {
    fun resolve(events: List<IrisStreamEvent>): IrisTurnResult {
        val turnRef = events.lastOrNull { it.turnId.isNotEmpty() }?.turnId
        val lastEnvelope = events.lastOrNull { it.hasEnvelope() }?.envelope
        events.firstOrNull { it.hasError() }?.let {
            return IrisTurnResult.Failed(turnRef, "${it.error.code}: ${it.error.message}")
        }
        val done =
            events.lastOrNull { it.hasDone() }?.done
                ?: return IrisTurnResult.Failed(turnRef, "stream ended without a terminal event")
        return when (done.outcome) {
            "done" -> IrisTurnResult.Succeeded(turnRef, lastEnvelope)
            "clarification" -> IrisTurnResult.AwaitingAgent(turnRef)
            "failed" -> IrisTurnResult.Failed(turnRef, "agent reported failure")
            // Only an explicit `done` is success. An unknown or empty terminal outcome
            // (a renamed/new iris-bff status, or a malformed event) fails loud rather than
            // delivering a non-success turn as a successful answer — the never-silent rule.
            else -> IrisTurnResult.Failed(turnRef, "unknown terminal outcome: '${done.outcome}'")
        }
    }
}
