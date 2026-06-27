package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.v1.StageKind

/** Registering a pipeline whose embedding dimension disagrees with the corpus. */
class EmbedConformanceException(
    message: String,
) : RuntimeException(message)

/** Registering a pipeline whose stages violate the data-flow ordering. */
class StageOrderException(
    message: String,
) : RuntimeException(message)

/**
 * The pipeline registry + per-source-feed binding (architecture §7). Each feed
 * declares ONE pipeline (per-source binding, not per-document auto-classify).
 *
 * Enforces the CONFORMED embedding dimension (architecture §11): a pipeline whose
 * `EmbedSpec` disagrees with the corpus is a config error at registration —
 * "disagreement = two corpora, not one". The corpus dimension is fixed once.
 */
class PipelineRegistry(
    val corpusEmbed: EmbedSpec,
) {
    private val byId = linkedMapOf<String, Pipeline>()
    private val byFeed = linkedMapOf<String, String>()

    fun register(pipeline: Pipeline) {
        if (pipeline.embed != corpusEmbed) {
            throw EmbedConformanceException(
                "pipeline '${pipeline.id}' embed ${pipeline.embed} disagrees with the conformed corpus dimension $corpusEmbed",
            )
        }
        validateStageOrder(pipeline)
        byId[pipeline.id] = pipeline
        byFeed[pipeline.sourceFeed] = pipeline.id
    }

    /**
     * A mis-ordered pipeline (e.g. COMPILE before LOAD) would otherwise no-op
     * silently — each tail stage guards `if (input.isEmpty()) return ctx` and the
     * run still reports SUCCEEDED with an empty wiki. Fail at registration instead:
     * every present stage must have all its data-flow prerequisites appear earlier.
     */
    private fun validateStageOrder(pipeline: Pipeline) {
        val present = pipeline.stages.toSet()
        pipeline.stages.forEachIndexed { i, stage ->
            for (prereq in STAGE_PREREQS[stage].orEmpty()) {
                val priorIndex = pipeline.stages.subList(0, i).lastIndexOf(prereq)
                if (prereq !in present) {
                    throw StageOrderException(
                        "pipeline '${pipeline.id}': stage $stage requires $prereq, which is absent",
                    )
                }
                if (priorIndex < 0) {
                    throw StageOrderException(
                        "pipeline '${pipeline.id}': stage $stage at position $i must come after its prerequisite $prereq",
                    )
                }
            }
        }
    }

    companion object {
        /** Data-flow prerequisites per stage (architecture §7 — head → conformed tail). */
        val STAGE_PREREQS: Map<StageKind, Set<StageKind>> =
            mapOf(
                StageKind.CHUNK to setOf(StageKind.EXTRACT),
                StageKind.LOAD to setOf(StageKind.CHUNK),
                StageKind.EMBED to setOf(StageKind.LOAD),
                StageKind.COMPILE to setOf(StageKind.LOAD),
                StageKind.RESOLVE to setOf(StageKind.COMPILE),
                StageKind.LINK to setOf(StageKind.RESOLVE),
            )
    }

    fun get(id: String): Pipeline? = byId[id]

    fun forFeed(feed: String): Pipeline? = byFeed[feed]?.let { byId[it] }

    fun all(): List<Pipeline> = byId.values.toList()
}
