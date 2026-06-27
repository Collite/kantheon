package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.v1.StageKind

/**
 * A pipeline stage (architecture §7): the head (extract/classify/chunk) varies
 * per source; the tail (embed/compile/link/resolve/load) is conformed. Each stage
 * transforms the [StageContext]; the [Runner] wraps it with timing + a
 * `StageRecord`. `itemsOut` reports how many items the stage produced (parts,
 * pages, …) for the lineage/metrics.
 */
interface Stage {
    val kind: StageKind

    suspend fun run(ctx: StageContext): StageContext

    /** Item count after this stage ran — for the StageRecord (parts / pages / 1). */
    fun itemsOut(ctx: StageContext): Long = 1
}
