package org.tatrman.kantheon.hebe.scheduler.offline

import org.tatrman.kantheon.hebe.config.LlmSource

/**
 * The LLM byok-fallback split (P2 Stage 2.5 T5; architecture §7.1). When the
 * runtime breaker is open under `llm.source = gateway_with_byok_fallback`, the
 * decision turns on **what kind of reasoning** the turn is:
 *
 *  - **Hebe's own routines** (heartbeat, summariser, fact-extract, ad-hoc local
 *    chat) → fall back to the configured BYOK model so Hebe stays useful offline.
 *  - **Constellation turns** (`kantheon_question` via iris-bff) → **never** fall
 *    back; they **defer** via the outbox and the user gets a never-silent
 *    "queued, will run when reconnected" note.
 *
 * Pure policy — no I/O. The caller acts on the [RoutingDecision].
 */
enum class TurnKind {
    /** Hebe's own reasoning — may fall back to BYOK when offline. */
    OWN_ROUTINE,

    /** A constellation turn via iris-bff — defers, never falls back. */
    CONSTELLATION,
}

sealed interface RoutingDecision {
    /** Use the gateway (online, or no fallback configured). */
    data object UseGateway : RoutingDecision

    /** Use the configured BYOK fallback model (own routine, breaker open). */
    data object UseByokFallback : RoutingDecision

    /** Defer via the outbox + emit the never-silent queued note (constellation, breaker open). */
    data object Defer : RoutingDecision

    /** Pure BYOK source (local) — there is no gateway in play. */
    data object UseByok : RoutingDecision
}

object FallbackRouter {
    fun decide(
        source: LlmSource,
        breakerOpen: Boolean,
        kind: TurnKind,
    ): RoutingDecision =
        when (source) {
            LlmSource.BYOK -> RoutingDecision.UseByok
            LlmSource.GATEWAY -> if (breakerOpen && kind == TurnKind.CONSTELLATION) RoutingDecision.Defer else RoutingDecision.UseGateway
            LlmSource.GATEWAY_WITH_BYOK_FALLBACK ->
                if (!breakerOpen) {
                    RoutingDecision.UseGateway
                } else {
                    when (kind) {
                        TurnKind.OWN_ROUTINE -> RoutingDecision.UseByokFallback
                        TurnKind.CONSTELLATION -> RoutingDecision.Defer
                    }
                }
        }

    /** The never-silent note a deferred constellation turn surfaces to the user. */
    const val QUEUED_NOTE = "queued, will run when reconnected"
}
