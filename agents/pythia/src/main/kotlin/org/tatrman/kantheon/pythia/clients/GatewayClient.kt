package org.tatrman.kantheon.pythia.clients

/**
 * GatewayClient tier-tag shim (contracts §5): maps `(tier, task_kind)` to a model
 * **tag** and a projected per-call cost until the gateway lands native
 * `(modality, tier, task_kind)` routing. Every call would carry `task_kind` as
 * metadata so the cutover is a client no-op. Pricing is injectable (ops-tunable;
 * `pythia.llm.map.*` config); the defaults are placeholders for project-and-reserve.
 */
class GatewayClient(
    private val perCallUsd: Map<String, Double> = DEFAULT_PRICING,
) {
    /** The model tag for a tier (CHAT.STRONG→opus, CHAT.CHEAP→haiku, EMBEDDING→embedding). */
    fun tagFor(tier: String): String =
        when (tier.lowercase()) {
            "strong" -> "opus"
            "cheap" -> "haiku"
            "embedding" -> "embedding"
            else -> "haiku"
        }

    /** Project the cost of a batch of [batchSize] calls at [tier] (project-and-reserve). */
    fun projectCost(
        tier: String,
        batchSize: Int,
    ): Double = (perCallUsd[tier.lowercase()] ?: perCallUsd.getValue("cheap")) * batchSize

    companion object {
        val DEFAULT_PRICING = mapOf("strong" to 0.05, "cheap" to 0.002, "embedding" to 0.0001)
    }
}
