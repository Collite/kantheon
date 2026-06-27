package org.tatrman.pinakes.pipeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.pinakes.v1.RunStatus
import org.tatrman.pinakes.v1.StageKind

/**
 * P3 Stage 3.1 T1 — the stage library + runner. A DAG of stage fakes executes in
 * order with per-stage `StageRecord`s; a mid-DAG failure yields PARTIAL/FAILED;
 * the run is resumable (re-run from the failed stage, not from scratch).
 */
class RunnerSpec :
    StringSpec({
        fun ctx() =
            StageContext(
                assetId = "a1",
                assetRef = "feed/a1",
                sourceFeed = "feed",
                mimeType = "text/plain",
                originalName = "doc.txt",
                notebookId = "feed-feed",
                bytes = "hello".toByteArray(),
            )

        // Records the order it ran in; appends its kind to parts.
        class RecordingStage(
            override val kind: StageKind,
            val order: MutableList<StageKind>,
        ) : Stage {
            override suspend fun run(ctx: StageContext): StageContext {
                order += kind
                return ctx.copy(parts = ctx.parts + kind.name)
            }
        }

        // Fails the first `failTimes` runs, then succeeds (proves resume).
        class FlakyStage(
            override val kind: StageKind,
            var failTimes: Int,
        ) : Stage {
            override suspend fun run(ctx: StageContext): StageContext {
                if (failTimes > 0) {
                    failTimes--
                    throw RuntimeException("flaky $kind")
                }
                return ctx
            }
        }

        "a DAG of stage fakes executes in order with per-stage records" {
            val order = mutableListOf<StageKind>()
            val library =
                StageLibrary(
                    listOf(
                        RecordingStage(StageKind.EXTRACT, order),
                        RecordingStage(StageKind.CHUNK, order),
                        RecordingStage(StageKind.LOAD, order),
                    ),
                )
            val pipeline =
                Pipeline(
                    "p",
                    "P",
                    "feed",
                    listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD),
                    EmbedSpec("m", 4, "1"),
                )

            val result = runBlocking { Runner(library) { 0 }.run(pipeline, ctx(), "run-1") }

            order shouldBe listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD)
            result.status shouldBe RunStatus.SUCCEEDED
            result.stages.map { it.kind } shouldBe listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD)
            result.stages.all { it.status == "SUCCEEDED" } shouldBe true
        }

        "a mid-DAG failure yields PARTIAL with the failed-stage index" {
            val order = mutableListOf<StageKind>()
            val library =
                StageLibrary(
                    listOf(
                        RecordingStage(StageKind.EXTRACT, order),
                        FlakyStage(StageKind.CHUNK, failTimes = 1),
                        RecordingStage(StageKind.LOAD, order),
                    ),
                )
            val pipeline =
                Pipeline(
                    "p",
                    "P",
                    "feed",
                    listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD),
                    EmbedSpec("m", 4, "1"),
                )

            val result = runBlocking { Runner(library) { 0 }.run(pipeline, ctx(), "run-1") }
            result.status shouldBe RunStatus.PARTIAL
            result.failedStageIndex shouldBe 1
            result.stages.last().status shouldBe "FAILED"
            order shouldBe listOf(StageKind.EXTRACT) // LOAD never ran
        }

        "a failed run resumes from the failed stage, not from scratch" {
            val order = mutableListOf<StageKind>()
            val flaky = FlakyStage(StageKind.CHUNK, failTimes = 1)
            val library =
                StageLibrary(
                    listOf(RecordingStage(StageKind.EXTRACT, order), flaky, RecordingStage(StageKind.LOAD, order)),
                )
            val pipeline =
                Pipeline(
                    "p",
                    "P",
                    "feed",
                    listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD),
                    EmbedSpec("m", 4, "1"),
                )
            val runner = Runner(library) { 0 }

            val first = runBlocking { runner.run(pipeline, ctx(), "run-1") }
            first.status shouldBe RunStatus.PARTIAL

            // Resume from the failed stage — EXTRACT does NOT run again.
            order.clear()
            val resumed =
                runBlocking { runner.run(pipeline, first.finalCtx, "run-1", fromIndex = first.failedStageIndex!!) }
            resumed.status shouldBe RunStatus.SUCCEEDED
            order shouldBe listOf(StageKind.LOAD) // only the post-CHUNK stages re-ran
        }

        "StageLibrary resolves stages by kind" {
            val lib = StageLibrary(listOf(RecordingStage(StageKind.EXTRACT, mutableListOf())))
            lib.has(StageKind.EXTRACT) shouldBe true
            lib.has(StageKind.LOAD) shouldBe false
            lib.stage(StageKind.EXTRACT).kind shouldBe StageKind.EXTRACT
        }
    })
