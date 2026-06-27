package org.tatrman.kantheon.pythia.synth

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import org.tatrman.kantheon.envelope.v1.Block
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.envelope.v1.FormatSpec
import org.tatrman.kantheon.pythia.events.EventEmitter
import org.tatrman.kantheon.pythia.events.Events
import org.tatrman.kantheon.pythia.plan.Prompts
import org.tatrman.kantheon.pythia.plan.PythiaModels
import org.tatrman.kantheon.pythia.v1.Conclusion
import org.tatrman.kantheon.pythia.v1.ConfidenceInfo
import org.tatrman.kantheon.pythia.v1.RenderableArtifact
import org.tatrman.kantheon.pythia.v1.StopReason
import java.util.UUID

/** What the synthesizer renders the conclusion from. */
data class SynthContext(
    val locale: String,
    val question: String,
    val supportedStatements: List<String>,
    val renderBlocks: List<Block>,
    val stopReason: StopReason,
    val confidence: ConfidenceInfo?,
    val evidenceStepIds: List<String>,
    val sourceTurnRef: String = "",
)

/**
 * Synthesizer v0 (Stage 2.4 T2): a STRONG-tier lead block + the render blocks,
 * streamed via `synthesizer_block_*` events, assembled into a `Conclusion` with an
 * **honest** `stop_reason` (never claims STOP_GOAL_REACHED on a budget truncation).
 */
class Synthesizer(
    private val executor: PromptExecutor,
    private val emitter: EventEmitter,
    private val prompts: Prompts = Prompts(),
) {
    suspend fun synthesize(
        investigationId: UUID,
        ctx: SynthContext,
    ): Conclusion {
        val leadText = lead(ctx)
        val leadBlock =
            Block
                .newBuilder()
                .setBlockId("synth-0")
                .setRole(BlockRole.PRIMARY)
                .setFormat(FormatSpec.newBuilder().setKind(FormatKind.MARKDOWN))
                .setText(leadText)
                // PD-9 provenance: attribute the conclusion to Pythia + link its evidence steps
                // (the artifact path / inbox hypothesis tree reads this, Stage 5.2 T3).
                .setProvenance(
                    org.tatrman.kantheon.common.v1.BlockProvenance
                        .newBuilder()
                        .setProducingAgentId("pythia")
                        .apply { ctx.evidenceStepIds.firstOrNull()?.let { stepId = it } },
                ).build()

        emitter.emit(investigationId, Events.synthesizerBlockStarted(0, "text"))
        emitter.emit(investigationId, Events.synthesizerBlockCompleted(0, leadBlock))

        val blocks = mutableListOf(leadBlock)
        ctx.renderBlocks.forEachIndexed { i, block ->
            val index = i + 1
            emitter.emit(investigationId, Events.synthesizerBlockStarted(index, block.format.kind.name))
            emitter.emit(investigationId, Events.synthesizerBlockCompleted(index, block))
            blocks += block
        }
        emitter.emit(investigationId, Events.synthesizerDone(blocks.size))

        val builder =
            Conclusion
                .newBuilder()
                .setPrimary(RenderableArtifact.newBuilder().addAllBlocks(blocks))
                .addAllEvidenceStepIds(ctx.evidenceStepIds)
                .setStopReason(ctx.stopReason)
                .setBudgetTruncated(
                    ctx.stopReason == StopReason.STOP_BUDGET || ctx.stopReason == StopReason.STOP_HARD_CAP,
                )
        if (ctx.sourceTurnRef.isNotBlank()) builder.sourceTurnRef = ctx.sourceTurnRef
        ctx.confidence?.let(builder::setConfidence)
        return builder.build()
    }

    private suspend fun lead(ctx: SynthContext): String {
        val template = prompts.load(ctx.locale, "synthesizer")
        val user =
            Prompts.substitute(
                template,
                mapOf(
                    "question" to ctx.question,
                    "findings" to ctx.supportedStatements.joinToString("; ").ifBlank { "no supported hypotheses" },
                    "stop_reason" to ctx.stopReason.name,
                ),
            )
        val p =
            prompt("pythia-synth") {
                system(
                    "You are Pythia's synthesizer. Write the lead paragraph of an analytical conclusion. " +
                        "Be honest about how the investigation ended (the stop reason). Return only the prose.",
                )
                user(user)
            }
        return executor
            .execute(p, PythiaModels.Strong, emptyList())
            .filterIsInstance<Message.Assistant>()
            .joinToString("\n") { it.content }
            .ifBlank { "Investigation complete." }
    }
}
