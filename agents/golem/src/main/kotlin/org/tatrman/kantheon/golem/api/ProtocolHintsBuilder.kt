package org.tatrman.kantheon.golem.api

import org.tatrman.kantheon.golem.graph.GolemTurnState
import org.tatrman.kantheon.protocol.v1.HintTiming
import org.tatrman.kantheon.protocol.v1.ProtocolHints

/**
 * Builds the PT-25/S-5 `protocol_hints` block golem attaches to every
 * `ConversationalResponse` (contracts §4).
 *
 * **Hints, not authority.** The BFF stores this verbatim and the assembler
 * cross-checks it against the federated sources, so the only rule that matters
 * here is *never claim more than golem actually knows*. Every field below is
 * either something golem holds first-hand or is left empty; nothing is inferred,
 * and nothing is reconstructed from a rendered artefact.
 */
object ProtocolHintsBuilder {
    /**
     * SQL longer than this is not inlined. `sql_ref` stays empty rather than
     * being faked: golem has no SQL store to hand out a reference into, so the
     * honest signal is "large, and not carried" — the assembler then falls back
     * to the translator's explain path (S-1) instead of trusting a truncated
     * string that would look complete.
     */
    private const val SQL_INLINE_MAX_CHARS = 20_000

    fun from(state: GolemTurnState): ProtocolHints {
        val b = ProtocolHints.newBuilder()

        // Golem's plan identity is its node ids — MiniPlan itself carries no id
        // (golem.proto MiniPlan), and ttr-query returns plans inline rather than
        // by handle, so there is no translator plan id to forward.
        state.plan?.nodesList?.forEach { b.addPlanIds(it.nodeId) }

        // llm_call_refs stays EMPTY, deliberately. Gateway row ids would have to
        // come back from `org.tatrman:ttr-llm-client.complete()`, which returns no
        // per-call metadata (the same gap as the X-Call-Purpose header, PT-24).
        // Empty is contract-sanctioned here; the assembler sources LLM calls from
        // the gateway by trace/turn ref instead and reports any it cannot attribute
        // as `LlmCallsSection.unattributable_count`.

        state.execution?.currentView?.sql?.takeIf { it.isNotBlank() }?.let { sql ->
            if (sql.length <= SQL_INLINE_MAX_CHARS) b.sqlInline = sql
        }

        // One timing per executed node, straight off the step records the turn
        // already produced — same numbers the FE sees, so a protocol can never
        // disagree with the step trace shown in the UI.
        state.execution?.stepRecords?.forEach { step ->
            b.addTimings(
                HintTiming
                    .newBuilder()
                    .setStep(step.nodeId)
                    .setDurationMs(step.latencyMs.coerceAtLeast(0)),
            )
        }

        return b.build()
    }
}
