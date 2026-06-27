package org.tatrman.kantheon.iris.api

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit

/**
 * Domain metrics for the routing/dispatch path (Stage 3.3, architecture §10.1).
 * Backed by a Micrometer [MeterRegistry] (Prometheus in prod, scraped at
 * `/metrics`). Default [NOOP] keeps tests + local boot zero-config.
 *
 *  - `iris_turn_duration_seconds{outcome}` — per-turn latency + outcome breakdown.
 *  - `iris_routing_needs_user_pick_total` — RoutingPickChip emissions (pick-rate
 *    numerator; divide by turn count).
 *  - `iris_routing_refusal_total{code}` — RefusalWithGaps by primary gap.
 *  - `iris_typed_action_total{kind}` — typed-action surface usage.
 *  - `iris_escalation_total` — InvestigateChip escalations.
 */
class RoutingMetrics(
    private val registry: MeterRegistry,
) {
    fun recordTurn(
        outcome: String,
        durationNanos: Long,
    ) {
        registry.timer("iris_turn_duration", "outcome", outcome).record(durationNanos, TimeUnit.NANOSECONDS)
    }

    fun recordNeedsUserPick() = registry.counter("iris_routing_needs_user_pick_total").increment()

    fun recordRefusal(code: String) = registry.counter("iris_routing_refusal_total", "code", code).increment()

    fun recordTypedAction(kind: String) = registry.counter("iris_typed_action_total", "kind", kind).increment()

    fun recordEscalation() = registry.counter("iris_escalation_total").increment()

    /** Pin/dashboard refresh outcome (PD-6, Stage 4.2). `result` = ok | error. */
    fun recordArtifactRefresh(result: String) =
        registry.counter("iris_artifact_refresh_total", "result", result).increment()

    /** Turn feedback (PD-3, Stage 4.3). */
    fun recordFeedback(
        agentId: String,
        verdict: String,
        reason: String,
    ) = registry.counter("iris_feedback_total", "agent_id", agentId, "verdict", verdict, "reason", reason).increment()

    companion object {
        /** No-op sink (an unscraped in-memory registry) for tests / local boot. */
        val NOOP = RoutingMetrics(SimpleMeterRegistry())
    }
}
