package org.tatrman.kantheon.themis.koog

/**
 * The LLM-gateway model keys Themis asks for.
 *
 * These are **generic tier keys**, not provider model names. The gateway catalog resolves them
 * per deployment, which is the point: a cluster picks which concrete model backs each tier
 * without any consumer changing code. `LlmGatewayPromptExecutor.mapModelToGatewayKey` emits
 * exactly this vocabulary (`opus→deep`, `sonnet→fast`, `haiku→mini`), so the direct
 * `LlmGatewayClient.complete` calls in the graph nodes now agree with the Koog executor path
 * instead of diverging from it.
 *
 * **Why this changed.** The nodes used to ask for `"haiku"` / `"sonnet"` directly. Those are
 * also valid catalog aliases — but they alias **Anthropic** models, so they only resolve on a
 * cluster holding Anthropic credentials. On a deployment with Azure credentials only, every
 * Themis LLM call came back as a provider error. `LlmGatewayClient.complete` never throws (it
 * returns `Result.failure`), so the graph nodes received an EMPTY string, every JSON parse
 * failed with "Expected start of the object '{', but had 'EOF'", and routing degraded into a
 * contentless clarification — a silent failure that looked like a bad model rather than a
 * missing credential.
 *
 * The tier semantics are unchanged: [CHEAP] is the classify/filter/route workhorse, [FAST] the
 * joint-inference step. Only the vocabulary moved from provider-flavoured to generic.
 */
object ThemisTiers {
    /** Cheap, high-volume calls: intent classification, span filtering, agent routing. */
    const val CHEAP = "mini"

    /** The heavier single call per turn: joint inference. */
    const val FAST = "fast"
}
