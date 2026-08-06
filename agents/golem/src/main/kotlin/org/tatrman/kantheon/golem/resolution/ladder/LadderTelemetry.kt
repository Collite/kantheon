package org.tatrman.kantheon.golem.resolution.ladder

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.tatrman.resolver.v1.GapRecord

/**
 * RV-P5.2 T5 — per-rung and per-gate spans.
 *
 * kantheon has a real OTel SDK, so unlike the resolver's these are LIVE, and together they
 * are **the ladder's whole health signal**: hypotheses in, hypotheses survived, gaps still
 * open. Two numbers per gate call, one span per rung invocation. P2.4 T5 named those numbers
 * on the server side where the SDK is inert; this is where they become queryable.
 *
 * The rung log and the spans deliberately tell the **same** story — the log is what an
 * emitted lattice carries to a plugin or a user, the spans are what an operator queries, and
 * a divergence between them is a bug in one of the two.
 */
const val RV_SPAN_RUNG: String = "golem.ladder.rung"
const val RV_SPAN_GATE: String = "golem.ladder.gate"

const val RV_RUNG: String = "rv.rung"
const val RV_GAP_KINDS: String = "rv.gap_kinds"
const val RV_HYPOTHESES_OUT: String = "rv.hypotheses_out"
const val RV_HYPOTHESES_IN: String = "rv.hypotheses_in"
const val RV_HYPOTHESES_SURVIVED: String = "rv.hypotheses_survived"
const val RV_GAPS_OPEN_AFTER: String = "rv.gaps_open_after"
const val RV_RUNG_FAILURE: String = "rv.rung_failure"

/**
 * One span per rung invocation. A rung that fails is an ERROR span carrying the reason — a
 * failed rung is not a verdict about a gap, and a span that looked clean would let the
 * difference disappear.
 */
suspend fun OpenTelemetry?.tracedRung(
    rung: String,
    gaps: List<GapRecord>,
    block: suspend () -> RungProposal,
): RungProposal {
    val sdk = this ?: return block()
    val span =
        sdk
            .getTracer("golem")
            .spanBuilder(RV_SPAN_RUNG)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(RV_RUNG, rung)
            .setAttribute(
                RV_GAP_KINDS,
                gaps
                    .map { it.kind.name.removePrefix("GAP_KIND_") }
                    .distinct()
                    .sorted()
                    .joinToString(","),
            ).startSpan()
    return try {
        val proposal = span.makeCurrent().use { block() }
        span.setAttribute(RV_HYPOTHESES_OUT, proposal.hypotheses.size.toLong())
        proposal.failure?.let {
            span.setAttribute(RV_RUNG_FAILURE, it)
            span.setStatus(StatusCode.ERROR, it)
        }
        proposal
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}

/** One span per gate call, carrying the two health numbers plus gaps still open after it. */
suspend fun OpenTelemetry?.tracedGate(
    hypothesesIn: Int,
    block: suspend () -> GateResult,
): GateResult {
    val sdk = this ?: return block()
    val span =
        sdk
            .getTracer("golem")
            .spanBuilder(RV_SPAN_GATE)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(RV_HYPOTHESES_IN, hypothesesIn.toLong())
            .startSpan()
    return try {
        val result = span.makeCurrent().use { block() }
        span.setAttribute(RV_HYPOTHESES_SURVIVED, result.outcomes.count { it.accepted }.toLong())
        span.setAttribute(RV_GAPS_OPEN_AFTER, openGaps(result.updatedGaps).size.toLong())
        result
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}
