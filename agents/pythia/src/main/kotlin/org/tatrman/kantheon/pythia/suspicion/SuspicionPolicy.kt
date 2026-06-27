package org.tatrman.kantheon.pythia.suspicion

import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.v1.Finding
import org.tatrman.kantheon.pythia.v1.SuspicionPolicy
import java.util.UUID

/** The action `on_suspicious_result` resolves to (design §3.1 policy table). */
enum class SuspicionAction { CONTINUE, WARN, HALT }

/**
 * Applies the `on_suspicious_result` policy (Stage 3.1 T4): CONTINUE (record +
 * proceed), WARN (Rule-6 + proceed), HALT (→ park AWAITING_USER_INPUT). Emits the
 * `suspicion_raised` + `finding` events. A non-suspicious result is a no-op.
 */
class SuspicionPolicyHandler(
    private val emitter: EventEmitter,
) {
    fun apply(
        investigationId: UUID,
        stepId: String,
        verdict: SuspicionVerdict,
        policy: SuspicionPolicy,
    ): SuspicionAction {
        if (!verdict.suspicious) return SuspicionAction.CONTINUE
        val action =
            when (policy) {
                SuspicionPolicy.SUSPICION_HALT -> SuspicionAction.HALT
                SuspicionPolicy.SUSPICION_CONTINUE -> SuspicionAction.CONTINUE
                else -> SuspicionAction.WARN // WARN is the default
            }
        val severity = if (action == SuspicionAction.HALT) "CRITICAL" else "WARN"
        emitter.emit(
            investigationId,
            Events.suspicionRaised(stepId, "result_anomaly", severity, verdict.reasons.joinToString("; ")),
        )
        emitter.emit(
            investigationId,
            Events.finding(
                Finding
                    .newBuilder()
                    .setId("finding-$stepId")
                    .setKind("SUSPICION")
                    .setSummary(verdict.reasons.joinToString("; "))
                    .addEvidenceStepIds(stepId)
                    .build(),
            ),
        )
        return action
    }
}
