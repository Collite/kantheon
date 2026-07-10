package org.tatrman.pinakes.pipeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.clients.CorpusPageWriter
import org.tatrman.pinakes.clients.LlmGatewayClient
import org.tatrman.pinakes.compile.ConceptRefDraft
import org.tatrman.pinakes.compile.EdgeDraft
import org.tatrman.pinakes.compile.Linker
import org.tatrman.pinakes.compile.PageDraft
import org.tatrman.pinakes.compile.PartInput
import org.tatrman.pinakes.compile.WikiCompiler
import org.tatrman.pinakes.pipeline.stages.LinkStage
import org.tatrman.pinakes.resolve.EntityResolver
import org.tatrman.pinakes.resolve.InMemoryConceptIndex
import org.tatrman.pinakes.resolve.ResolvedPage

/**
 * P3 Stage 3.3 T2/T3 — compile hardening: the token-budget degrade-to-mechanical
 * (corpus stays queryable) and re-ingest COMPOUNDING (a merged entity is not
 * duplicated across overlapping sources).
 */
class CompileHardeningSpec :
    StringSpec({
        "an over-budget compile degrades to a mechanical SUMMARY without calling the LLM" {
            val neverCalled =
                object : LlmGatewayClient {
                    override suspend fun complete(
                        systemPrompt: String,
                        userPrompt: String,
                    ): String = throw AssertionError("LLM must not be called when over budget")
                }
            // tokenBudget = 1 forces the degrade before any LLM call.
            val compiler = WikiCompiler(neverCalled, systemPrompt = "sys", tokenBudget = 1)
            val result = runBlocking { compiler.compile(listOf(PartInput(1, "a long enough chunk of text"))) }

            result.degraded shouldBe true
            result.llmCalled shouldBe false
            result.pages.size shouldBe 1
            result.pages.first().kind shouldBe PageKind.SUMMARY // queryable, just thin
        }

        "re-ingesting an overlapping source does not duplicate the merged entity (compounding)" {
            // A capturing writer counts how many entity pages it is asked to write.
            val writtenEntityTitles = mutableListOf<String>()
            val writer =
                object : CorpusPageWriter {
                    private var nextId = 100L

                    override suspend fun writePages(
                        notebookId: String,
                        sourceId: Long,
                        resolved: List<ResolvedPage>,
                        edges: List<EdgeDraft>,
                    ): List<Long> {
                        resolved
                            .filter {
                                it.draft.conceptRef != null
                            }.forEach { writtenEntityTitles += it.draft.title }
                        return resolved.map { nextId++ }
                    }
                }
            val index = InMemoryConceptIndex()
            val resolver = EntityResolver(index)
            val link = LinkStage(Linker(), writer, index)

            fun kaufland() =
                PageDraft(
                    0,
                    PageKind.ENTITY,
                    "Kaufland",
                    "# Kaufland",
                    listOf(1),
                    ConceptRefDraft("customer", "wiki:kaufland", "Kaufland"),
                )

            fun ctxFor(drafts: List<PageDraft>) =
                StageContext(
                    "a",
                    "ref",
                    "feed",
                    "text/plain",
                    "doc",
                    "feed-feed",
                    "x".toByteArray(),
                    sourceId = 1,
                    partIds = listOf(1),
                ).copy(resolvedPages = resolver.resolve(drafts))

            // First source: Kaufland is NEW → written once.
            runBlocking { link.run(ctxFor(listOf(kaufland()))) }
            // Overlapping source: Kaufland MERGES → NOT written again (no duplicate page).
            runBlocking { link.run(ctxFor(listOf(kaufland()))) }

            writtenEntityTitles shouldBe listOf("Kaufland") // exactly one Kaufland page ever written
        }
    })
