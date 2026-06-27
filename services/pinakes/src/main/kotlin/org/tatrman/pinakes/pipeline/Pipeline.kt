package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.v1.StageKind

/** The conformed embedding dimension (contracts §2 `EmbedConfig`, architecture §11). */
data class EmbedSpec(
    val modelId: String,
    val dimensions: Int,
    val modelVersion: String,
)

/**
 * A named pipeline DAG (contracts §2 `Pipeline`): a head that varies per source
 * feed + the conformed tail. `sourceFeed` is the per-source binding (one feed →
 * one pipeline, architecture §7). `embed` is the conformed corpus dimension —
 * all pipelines feeding one corpus must agree (enforced at registration).
 */
data class Pipeline(
    val id: String,
    val displayName: String,
    val sourceFeed: String,
    val stages: List<StageKind>,
    val embed: EmbedSpec,
)
