package org.tatrman.pinakes.pipeline.stages

import org.tatrman.pinakes.clients.KallimachosWriteClient
import org.tatrman.pinakes.pipeline.Stage
import org.tatrman.pinakes.pipeline.StageContext
import org.tatrman.pinakes.v1.StageKind

/**
 * The mechanical head + the load/embed tail (architecture §7). EXTRACT decodes
 * bytes → text; CHUNK splits paragraphs; LOAD writes source + parts via the
 * Kallimachos `LoadApi` (minting corpus ids); EMBED triggers the warehouse to
 * embed the loaded source (the non-atomic edge, P2). COMPILE/LINK/RESOLVE are
 * stubs here — their LLM bodies land in S3.2.
 */
class ExtractStage : Stage {
    override val kind = StageKind.EXTRACT

    override suspend fun run(ctx: StageContext): StageContext =
        ctx.copy(
            text =
                ctx.bytes
                    .decodeToString()
                    .replace("\r\n", "\n")
                    .trim(),
        )
}

class ChunkStage : Stage {
    override val kind = StageKind.CHUNK

    override suspend fun run(ctx: StageContext): StageContext = ctx.copy(parts = chunk(ctx.text ?: ""))

    override fun itemsOut(ctx: StageContext): Long = ctx.parts.size.toLong()

    private fun chunk(text: String): List<String> =
        if (text.isBlank()) emptyList() else text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotBlank() }
}

class LoadStage(
    private val writeClient: KallimachosWriteClient,
) : Stage {
    override val kind = StageKind.LOAD

    override suspend fun run(ctx: StageContext): StageContext {
        writeClient.ensureNotebook(ctx.notebookId, "Feed: ${ctx.sourceFeed}")
        val outcome =
            writeClient.loadSource(
                notebookId = ctx.notebookId,
                title = ctx.originalName,
                mimeType = ctx.mimeType,
                assetRef = ctx.assetRef,
                parts = ctx.parts,
            )
        return ctx.copy(sourceId = outcome.sourceId, partIds = outcome.partIds)
    }

    override fun itemsOut(ctx: StageContext): Long = ctx.partIds.size.toLong()
}

class EmbedStage(
    private val writeClient: KallimachosWriteClient,
) : Stage {
    override val kind = StageKind.EMBED

    override suspend fun run(ctx: StageContext): StageContext {
        val id = ctx.sourceId ?: return ctx // nothing loaded — skip (mechanical-only run)
        writeClient.embedSource(id)
        return ctx.copy(embedded = true)
    }
}

/** Head branch detection — a no-op pass-through at v1 (per-feed binding, not auto-classify). */
class ClassifyStage : Stage {
    override val kind = StageKind.CLASSIFY

    override suspend fun run(ctx: StageContext): StageContext = ctx
}
