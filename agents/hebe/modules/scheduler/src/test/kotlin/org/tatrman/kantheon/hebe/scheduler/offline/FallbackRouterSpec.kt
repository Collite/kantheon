package org.tatrman.kantheon.hebe.scheduler.offline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.hebe.config.LlmSource

/**
 * The byok-fallback split (P2 Stage 2.5 T5): own routines fall back when offline;
 * constellation turns never fall back — they defer.
 */
class FallbackRouterSpec : StringSpec({

    "online: everything uses the gateway" {
        FallbackRouter.decide(LlmSource.GATEWAY_WITH_BYOK_FALLBACK, breakerOpen = false, TurnKind.OWN_ROUTINE) shouldBe
            RoutingDecision.UseGateway
        FallbackRouter.decide(LlmSource.GATEWAY_WITH_BYOK_FALLBACK, breakerOpen = false, TurnKind.CONSTELLATION) shouldBe
            RoutingDecision.UseGateway
    }

    "offline + own routine → byok fallback (Hebe stays useful)" {
        FallbackRouter.decide(LlmSource.GATEWAY_WITH_BYOK_FALLBACK, breakerOpen = true, TurnKind.OWN_ROUTINE) shouldBe
            RoutingDecision.UseByokFallback
    }

    "offline + constellation turn → defer (never falls back, never silent)" {
        FallbackRouter.decide(LlmSource.GATEWAY_WITH_BYOK_FALLBACK, breakerOpen = true, TurnKind.CONSTELLATION) shouldBe
            RoutingDecision.Defer
    }

    "plain gateway source never falls back; a constellation turn still defers when offline" {
        FallbackRouter.decide(LlmSource.GATEWAY, breakerOpen = true, TurnKind.OWN_ROUTINE) shouldBe RoutingDecision.UseGateway
        FallbackRouter.decide(LlmSource.GATEWAY, breakerOpen = true, TurnKind.CONSTELLATION) shouldBe RoutingDecision.Defer
    }

    "byok source is always local" {
        FallbackRouter.decide(LlmSource.BYOK, breakerOpen = false, TurnKind.OWN_ROUTINE) shouldBe RoutingDecision.UseByok
        FallbackRouter.decide(LlmSource.BYOK, breakerOpen = true, TurnKind.OWN_ROUTINE) shouldBe RoutingDecision.UseByok
    }
})
