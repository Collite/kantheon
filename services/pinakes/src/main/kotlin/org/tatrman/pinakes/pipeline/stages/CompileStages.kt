package org.tatrman.pinakes.pipeline.stages

import io.micrometer.core.instrument.MeterRegistry
import org.tatrman.pinakes.clients.CorpusPageWriter
import org.tatrman.pinakes.compile.ContradictionDetector
import org.tatrman.pinakes.compile.Linker
import org.tatrman.pinakes.compile.PartInput
import org.tatrman.pinakes.compile.WikiCompiler
import org.tatrman.pinakes.pipeline.Stage
import org.tatrman.pinakes.pipeline.StageContext
import org.tatrman.pinakes.resolve.ConceptIndex
import org.tatrman.pinakes.resolve.EntityResolver
import org.slf4j.LoggerFactory
import org.tatrman.pinakes.resolve.ResolveOutcome
import org.tatrman.pinakes.v1.StageKind

/**
 * The LLM compile tail (architecture §7, S3.2/S3.3): COMPILE authors wiki pages
 * (token-budget guarded, degrade-to-mechanical on failure); RESOLVE reconciles
 * entities globally; LINK derives content + CONTRADICTS edges and writes the
 * NEW pages via the LoadApi — re-ingestion COMPOUNDS (merged entities are not
 * duplicated, architecture §6).
 */
class CompileStage(
    private val compiler: WikiCompiler,
    private val meters: MeterRegistry? = null,
) : Stage {
    override val kind = StageKind.COMPILE
    private val log = LoggerFactory.getLogger(CompileStage::class.java)

    override suspend fun run(ctx: StageContext): StageContext {
        if (ctx.sourceId == null || ctx.partIds.isEmpty()) return ctx
        // LOAD's minted ids must align 1:1 with CHUNK's texts, in order — a zip
        // silently truncates a mismatch and would mis-cite DERIVED_FROM provenance.
        // Surface the mismatch rather than compile mis-aligned pages.
        require(ctx.partIds.size == ctx.parts.size) {
            "compile: ${ctx.partIds.size} part ids != ${ctx.parts.size} chunk texts for source ${ctx.sourceId} " +
                "(LOAD/CHUNK disagreement — provenance would mis-cite)"
        }
        log.debug("compile: {} parts for source {}", ctx.partIds.size, ctx.sourceId)
        val parts = ctx.partIds.zip(ctx.parts).map { (id, text) -> PartInput(id, text) }
        val result = compiler.compile(parts)
        val outcome = if (result.degraded) "degraded" else "success"
        meters?.counter("pinakes_compile_llm_calls_total", "result", outcome)?.increment()
        return ctx.copy(pageDrafts = result.pages, compileDegraded = result.degraded)
    }

    override fun itemsOut(ctx: StageContext): Long = ctx.pageDrafts.size.toLong()
}

class ResolveStage(
    private val resolver: EntityResolver,
    private val meters: MeterRegistry? = null,
) : Stage {
    override val kind = StageKind.RESOLVE

    override suspend fun run(ctx: StageContext): StageContext {
        if (ctx.pageDrafts.isEmpty()) return ctx
        val resolved = resolver.resolve(ctx.pageDrafts)
        resolved.forEach { rp ->
            val outcome = if (rp.outcome == ResolveOutcome.MERGED) "merged" else "new"
            meters?.counter("pinakes_entities_resolved_total", "outcome", outcome)?.increment()
        }
        return ctx.copy(resolvedPages = resolved)
    }

    override fun itemsOut(ctx: StageContext): Long = ctx.resolvedPages.size.toLong()
}

class LinkStage(
    private val linker: Linker,
    private val writer: CorpusPageWriter,
    private val index: ConceptIndex,
    private val contradictions: ContradictionDetector? = null,
) : Stage {
    override val kind = StageKind.LINK

    override suspend fun run(ctx: StageContext): StageContext {
        val resolved = ctx.resolvedPages
        if (resolved.isEmpty() || ctx.sourceId == null) return ctx

        // COMPOUNDING: write only NEW pages — a merged (duplicate) entity reuses
        // the existing page, it is not written again (architecture §6).
        val newPages = resolved.filter { it.outcome == ResolveOutcome.NEW }
        if (newPages.isEmpty()) return ctx

        val edges =
            linker.link(newPages) +
                (contradictions?.detect(newPages) ?: emptyList())
        val pageIds = writer.writePages(ctx.notebookId, ctx.sourceId, newPages, edges)

        // Register the written entity pages so later feeds MERGE into them.
        newPages.zip(pageIds).forEach { (rp, pageId) ->
            rp.draft.conceptRef?.let { index.register(it.entityType, it.displayLabel, it.entityId, pageId) }
        }
        return ctx.copy(pageIds = pageIds)
    }

    override fun itemsOut(ctx: StageContext): Long = ctx.pageIds.size.toLong()
}
