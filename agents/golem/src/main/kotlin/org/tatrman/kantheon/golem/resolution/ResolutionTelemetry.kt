package org.tatrman.kantheon.golem.resolution

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.tatrman.resolver.v1.GapKind

/**
 * RV-P5.1 T4 — the `golem.callResolutionCore` span.
 *
 * kantheon has a real OTel SDK (unlike the resolver, whose spans are inert), so this one is
 * LIVE and it is the phase's first health signal. What it carries is chosen so the ladder's
 * later numbers have a baseline to sit against:
 *
 * - **gap counts by kind** — the input P5.2's loop works from. A rising `g1_unbound` across
 *   an estate is the vocabulary telling you what it is missing, which is the whole feedback
 *   loop RV-P7 formalises.
 * - **`rv.llm_invocations = 0`, always** — the node never spends budget, and asserting it as
 *   an attribute rather than a comment means a future edit that adds an LLM call here shows
 *   up in the traces rather than in a review.
 * - **the RV-39 layer tuple** — same tuple + same question ⇒ same lattice (S-1). Without it
 *   on the span, a lattice seen in Grafana is not reproducible.
 */
const val RV_SPAN_CALL_CORE: String = "golem.callResolutionCore"
const val RV_LLM_INVOCATIONS: String = "rv.llm_invocations"
const val RV_GAPS_TOTAL: String = "rv.gaps.total"
const val RV_MENTIONS_TOTAL: String = "rv.mentions.total"
const val RV_VALUES_TOTAL: String = "rv.values.total"
const val RV_LEXICON_ARTIFACT_HASH: String = "rv.lexicon_artifact_hash"
const val RV_OVERLAY_VERSION: String = "rv.overlay_version"
const val RV_DEGRADE_CODE: String = "rv.degrade_code"

/** `GAP_KIND_G1_UNBOUND` → `rv.gaps.g1_unbound`. */
fun gapAttributeName(kind: GapKind): String = "rv.gaps." + kind.name.removePrefix("GAP_KIND_").lowercase()

/**
 * Runs the core call inside the span and annotates it from the result. A degrade is recorded
 * as an attribute and an ERROR status — not as a thrown exception, because the turn survives
 * it; a span that looked clean would hide the one thing an operator needs to see.
 */
suspend fun OpenTelemetry?.tracedResolutionCore(block: suspend () -> CoreCallResult): CoreCallResult {
    val sdk = this ?: return block()
    val span =
        sdk
            .getTracer("golem")
            .spanBuilder(RV_SPAN_CALL_CORE)
            .setSpanKind(SpanKind.CLIENT)
            .startSpan()
    return try {
        val result = span.makeCurrent().use { block() }
        // The node never spends LLM budget. Stated on every span, including degraded ones.
        span.setAttribute(RV_LLM_INVOCATIONS, 0L)
        result.degrade?.let {
            span.setAttribute(RV_DEGRADE_CODE, it.code)
            span.setStatus(StatusCode.ERROR, it.message)
        }
        result.lattice?.let { lattice ->
            span.setAttribute(RV_MENTIONS_TOTAL, lattice.mentionsCount.toLong())
            span.setAttribute(RV_VALUES_TOTAL, lattice.valuesCount.toLong())
            span.setAttribute(RV_GAPS_TOTAL, lattice.gapsCount.toLong())
            // Every kind that OCCURRED, not every kind that exists: a wall of zeroes costs
            // cardinality and hides the one non-zero.
            lattice.gapsList.groupingBy { it.kind }.eachCount().forEach { (kind, count) ->
                span.setAttribute(gapAttributeName(kind), count.toLong())
            }
        }
        result.provenance?.let { provenance ->
            span.setAttribute(RV_LEXICON_ARTIFACT_HASH, provenance.lexiconArtifactHash)
            // ABSENT until RV-P6 — an attribute set to "" would read as "there is one, empty".
            provenance.overlayVersion?.let { span.setAttribute(RV_OVERLAY_VERSION, it) }
        }
        result
    } catch (e: Throwable) {
        span.recordException(e)
        span.setStatus(StatusCode.ERROR)
        throw e
    } finally {
        span.end()
    }
}
