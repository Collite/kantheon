package org.tatrman.kantheon.envelope.render

import org.tatrman.kantheon.common.v1.BlockProvenance
import org.tatrman.kantheon.common.v1.ViewProvenance
import org.tatrman.kantheon.envelope.render.catalog.FormatResult
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole

/**
 * Lifts a [FormatResult] into an envelope/v1 [Block], stamping `Block.provenance`
 * (PD-9, 2026-06-12) at format time.
 *
 * envelope-render fills the **uniform** provenance: the view the block renders and
 * the producing agent id; callers enrich with step / hypothesis / model refs
 * (Pythia) before emission. `source_tables` come from the pipeline's
 * `used_objects` where available. Provenance is **optional** — a `null`
 * [provenance] yields a block with no provenance field, which the FE renders as
 * "provenance unavailable", never an error.
 */
fun FormatResult.toBlock(
    blockId: String,
    role: BlockRole = BlockRole.PRIMARY,
    caption: String? = null,
    provenance: BlockProvenanceInput? = null,
): Block {
    val b =
        Block
            .newBuilder()
            .setBlockId(blockId)
            .setRole(role)
            .setFormat(format)
    text?.let { b.text = it }
    contentJson?.let { b.contentJson = it }
    caption?.let { b.caption = it }
    provenance?.let { b.provenance = it.toProto() }
    return b.build()
}

/**
 * Caller-supplied provenance inputs. [producingAgentId] is the one always-present
 * field; everything else is enriched per agent (Pythia adds step/hypothesis/model
 * refs; `sourceTables` from `PipelineContext.used_objects`).
 */
data class BlockProvenanceInput(
    val producingAgentId: String,
    val view: ViewProvenance? = null,
    val stepId: String? = null,
    val hypothesisId: String? = null,
    val modelRef: String? = null,
    val sourceTables: List<String> = emptyList(),
    val modelVersion: String? = null,
    val computedAt: String? = null,
) {
    fun toProto(): BlockProvenance {
        val b = BlockProvenance.newBuilder().setProducingAgentId(producingAgentId)
        view?.let { b.view = it }
        stepId?.let { b.stepId = it }
        hypothesisId?.let { b.hypothesisId = it }
        modelRef?.let { b.modelRef = it }
        b.addAllSourceTables(sourceTables)
        modelVersion?.let { b.modelVersion = it }
        computedAt?.let { b.computedAt = it }
        return b.build()
    }
}
