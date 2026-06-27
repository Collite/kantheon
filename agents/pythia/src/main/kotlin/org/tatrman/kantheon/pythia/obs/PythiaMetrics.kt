package org.tatrman.kantheon.pythia.obs

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * The Pythia observability metric set (architecture §8) over a Micrometer
 * [MeterRegistry] — the exact metric names the dashboard expects. Unit-testable
 * against a `SimpleMeterRegistry`; the live Prometheus registry is wired in
 * `Application`. Counters carry the documented tag dimensions; durations are
 * histograms (`Timer` / `DistributionSummary`).
 */
class PythiaMetrics(
    private val registry: MeterRegistry,
) {
    fun investigationTerminal(
        status: String,
        intentKind: String,
        callerKind: String,
    ) {
        registry
            .counter(
                "pythia_investigations_total",
                "status",
                status,
                "intent_kind",
                intentKind,
                "caller_kind",
                callerKind,
            ).increment()
    }

    fun investigationDuration(
        intentKind: String,
        ms: Long,
    ) {
        Timer
            .builder("pythia_investigation_duration_ms")
            .tag("intent_kind", intentKind)
            .register(registry)
            .record(ms, TimeUnit.MILLISECONDS)
    }

    fun step(
        nodeKind: String,
        status: String,
    ) {
        registry.counter("pythia_steps_total", "node_kind", nodeKind, "status", status).increment()
    }

    fun batchParallelism(size: Int) {
        DistributionSummary.builder("pythia_batch_parallelism").register(registry).record(size.toDouble())
    }

    fun llmCall(
        tier: String,
        taskKind: String,
        costUsd: Double,
    ) {
        registry.counter("pythia_llm_calls_total", "tier", tier, "task_kind", taskKind).increment()
        registry.counter("pythia_llm_cost_usd_total", "task_kind", taskKind).increment(costUsd)
    }

    fun budgetHalt(ladderStep: String) {
        registry.counter("pythia_budget_halts_total", "ladder_step", ladderStep).increment()
    }

    // NB: `pythia_hypotheses_total{terminal_status}` is emitted directly by HypothesisEvaluator
    // (it holds the per-hypothesis verdict at the moment it's decided), so there is no helper here.

    fun planRevision(kind: String) {
        registry.counter("pythia_plan_revisions_total", "kind", kind).increment()
    }

    fun awaitingEntered(state: String) {
        registry.counter("pythia_awaiting_total", "state", state).increment()
    }

    fun awaitingDuration(
        state: String,
        ms: Long,
    ) {
        Timer
            .builder("pythia_awaiting_duration_ms")
            .tag("state", state)
            .register(registry)
            .record(ms, TimeUnit.MILLISECONDS)
    }

    fun handleMaterialisation(
        from: String,
        to: String,
    ) {
        registry.counter("pythia_handle_materialisations_total", "from", from, "to", to).increment()
    }

    fun checkpointBytes(bytes: Long) {
        DistributionSummary.builder("pythia_checkpoint_bytes").register(registry).record(bytes.toDouble())
    }

    // `pythia_event_lag_seconds` (NATS produce→consume lag) lands with the real JetStream
    // consumer; v1 ships LoggingNatsPublisher (integration-deferred), so there is nothing to
    // measure lag against yet. Added here when the consumer is wired, not before — no dead metric.
}
