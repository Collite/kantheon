package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.v1.RunStatus
import org.tatrman.pinakes.v1.StageKind

data class StageRunRecord(
    val stageId: String,
    val kind: StageKind,
    val status: String,
    val itemsIn: Long,
    val itemsOut: Long,
    val latencyMs: Long,
    val costUsd: Double = 0.0,
    val error: String? = null,
)

data class RunResult(
    val runId: String,
    val pipelineId: String,
    val status: RunStatus,
    val stages: List<StageRunRecord>,
    val finalCtx: StageContext,
    val failedStageIndex: Int? = null,
)

/**
 * Executes a pipeline's stage DAG over a [StageContext], recording a per-stage
 * `StageRecord` (status / items in-out / latency / cost). A stage throw stops the
 * run: `FAILED` if the first executed stage fails, `PARTIAL` if some stages
 * already succeeded. The run is **resumable** — [RunResult.failedStageIndex] +
 * [RunResult.finalCtx] let a caller re-run from the failed stage, not from
 * scratch (architecture §7; the compile stage is expensive, so resume matters).
 */
class Runner(
    private val library: StageLibrary,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        pipeline: Pipeline,
        initial: StageContext,
        runId: String,
        fromIndex: Int = 0,
    ): RunResult {
        var ctx = initial
        val records = mutableListOf<StageRunRecord>()

        for (i in fromIndex until pipeline.stages.size) {
            val kind = pipeline.stages[i]
            val stage = library.stage(kind)
            val itemsIn =
                ctx.parts.size
                    .coerceAtLeast(1)
                    .toLong()
            val t0 = clockMs()
            try {
                ctx = stage.run(ctx)
                records += record(kind, i, "SUCCEEDED", itemsIn, stage.itemsOut(ctx), clockMs() - t0)
            } catch (e: Exception) {
                records += record(kind, i, "FAILED", itemsIn, 0, clockMs() - t0, e.message)
                val status = if (i > fromIndex || records.size > 1) RunStatus.PARTIAL else RunStatus.FAILED
                return RunResult(runId, pipeline.id, status, records, ctx, failedStageIndex = i)
            }
        }
        return RunResult(runId, pipeline.id, RunStatus.SUCCEEDED, records, ctx)
    }

    private fun record(
        kind: StageKind,
        index: Int,
        status: String,
        itemsIn: Long,
        itemsOut: Long,
        latencyMs: Long,
        error: String? = null,
    ) = StageRunRecord("${kind.name.lowercase()}-$index", kind, status, itemsIn, itemsOut, latencyMs, error = error)
}
