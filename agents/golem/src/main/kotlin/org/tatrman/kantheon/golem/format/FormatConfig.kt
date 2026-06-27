package org.tatrman.kantheon.golem.format

/**
 * Format-pipeline tunables (`golem.format.*`, S3.1 §10 Δ2/Δ5). Defaults mirror the
 * ai-platform `config.py` values: chart-on-compare on, chip top-up on below 2 chips.
 */
data class FormatConfig(
    val chartOnCompare: Boolean = true,
    val chipMinBeforeTopup: Int = 2,
    val chipLlmTopupEnabled: Boolean = true,
    /** Hard budget (ms) for the cosmetic LLM chip top-up — bounds the render path independently
     *  of the planner timeout, so a slow gateway can't stall a turn for optional chips. */
    val chipTopupTimeoutMs: Long = 1500,
)
