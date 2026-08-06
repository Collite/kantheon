package org.tatrman.kantheon.golem.resolution.ladder

import io.opentelemetry.api.OpenTelemetry
import org.tatrman.kantheon.golem.resolution.ResolutionCoreClient
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState

/**
 * RV-P5.2 — [GateCall] over the real `resolve.gate:v1`.
 *
 * Thin on purpose: the loop must not know about protobuf builders, and the gate's own
 * contract (stateless, the caller carries the lattice) is already the shape [GateCall] wants.
 * The only translation is lifting `HypothesisOutcome` into [HypothesisVerdict] so the loop
 * can branch on `LOOKUP_FAILED` without importing the resolver package.
 */
class CoreGateCall(
    private val client: ResolutionCoreClient,
    private val otel: OpenTelemetry? = null,
) : GateCall {
    override suspend fun gate(
        lattice: ResolutionState,
        hypotheses: List<Hypothesis>,
    ): GateResult =
        otel.tracedGate(hypotheses.size) {
            val response =
                client.gate(
                    GateRequest
                        .newBuilder()
                        .setLattice(lattice)
                        .addAllHypotheses(hypotheses)
                        .build(),
                )
            GateResult(
                gatedBindings = response.gatedBindingsList.toList(),
                updatedGaps = response.updatedGapsList.toList(),
                rungLogEntry = if (response.hasRungLogEntry()) response.rungLogEntry else null,
                outcomes =
                    response.outcomesList.map {
                        HypothesisVerdict(hypothesis = it.hypothesis, accepted = it.accepted, reason = it.reason)
                    },
            )
        }
}
