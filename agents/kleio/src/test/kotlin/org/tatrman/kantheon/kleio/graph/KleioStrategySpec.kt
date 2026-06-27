package org.tatrman.kantheon.kleio.graph

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.kleio.clients.GroundedAnswer
import org.tatrman.kantheon.kleio.clients.KallimachosMcpClient
import org.tatrman.kantheon.kleio.clients.KleioLlmClient
import org.tatrman.kantheon.kleio.clients.RetrievedChunk

/**
 * P5 Stage 5.1 T3/T4/T5 — the grounded turn. Scope→Retrieve→GroundedAnswer→Render
 * yields a GroundedResponse citing ONLY retrieved nodes (uncited/hallucinated
 * citations are dropped at render — the grounding contract); nothing retrieved →
 * NO_GROUNDING CALLOUT, no fabricated citations.
 */
class KleioStrategySpec :
    StringSpec({
        fun chunk(
            partId: Long,
            sourceId: Long,
            pageId: Long? = null,
            score: Double = 0.9,
        ) = RetrievedChunk(
            partId,
            sourceId,
            pageId,
            "text $partId",
            score,
            "Doc$sourceId",
            "¶$partId",
            "kallimachos://nb/$sourceId/$partId",
        )

        fun retriever(chunks: List<RetrievedChunk>) =
            object : KallimachosMcpClient {
                override suspend fun getContext(
                    notebookId: String,
                    question: String,
                    k: Int,
                    bearer: String?,
                ): List<RetrievedChunk> = chunks
            }

        fun llm(answer: GroundedAnswer) =
            object : KleioLlmClient {
                override suspend fun answer(
                    question: String,
                    chunks: List<RetrievedChunk>,
                ): GroundedAnswer = answer
            }

        fun state(
            minScore: Double = 0.0,
            notebookId: String = "nb",
        ) = KleioTurnState("turn-1", "what about churn?", notebookId, "bearer", k = 8, minScore = minScore)

        "a grounded turn cites only retrieved nodes; hallucinated citations are dropped" {
            val retrieved = listOf(chunk(11, 3), chunk(12, 3))
            // The model cites a real part (11), a real page (none here), and a HALLUCINATED part (999).
            val strategy =
                KleioStrategy(
                    retriever(retrieved),
                    llm(
                        GroundedAnswer(
                            "Churn rose in Q3 [11].",
                            citedPartIds = listOf(11, 999),
                            citedPageIds = emptyList(),
                        ),
                    ),
                )

            val out = runBlocking { strategy.run(state()) }

            out.status shouldBe KleioStatus.DONE
            // Only part 11 survives — 999 was never retrieved, so it is dropped.
            out.sourcesUsed.map { it.partId } shouldContainExactlyInAnyOrder listOf(11L)
            // The drilldowns (citations) point only at the retrieved set.
            out.envelope.drilldownsList.map { it.argMappingMap["partId"] } shouldContainExactlyInAnyOrder listOf("11")
            out.envelope.agentId shouldBe "kleio"
            out.envelope.text shouldContain "Churn rose"
        }

        "a page citation is honoured when the page was retrieved" {
            val retrieved = listOf(chunk(11, 3, pageId = 42))
            val strategy =
                KleioStrategy(
                    retriever(retrieved),
                    llm(GroundedAnswer("Per the wiki page.", emptyList(), citedPageIds = listOf(42))),
                )
            val out = runBlocking { strategy.run(state()) }
            out.sourcesUsed.first().pageId shouldBe 42L
        }

        "nothing retrieved above min-score → NO_GROUNDING CALLOUT, no fabricated citations" {
            // Retrieval returns a low-score chunk; min-score 0.5 filters it out.
            val strategy =
                KleioStrategy(
                    retriever(listOf(chunk(11, 3, score = 0.2))),
                    llm(GroundedAnswer("invented", listOf(11), emptyList())),
                )
            val out = runBlocking { strategy.run(state(minScore = 0.5)) }

            out.status shouldBe KleioStatus.NO_GROUNDING
            out.sourcesUsed.isEmpty() shouldBe true
            out.envelope.drilldownsList.isEmpty() shouldBe true
            out.envelope.text shouldContain "couldn't find"
        }

        "an empty mart (no chunks) → NO_GROUNDING" {
            val strategy = KleioStrategy(retriever(emptyList()), llm(GroundedAnswer("x", emptyList(), emptyList())))
            runBlocking { strategy.run(state()) }.status shouldBe KleioStatus.NO_GROUNDING
        }

        "a missing notebook fails the turn (scope)" {
            val strategy = KleioStrategy(retriever(emptyList()), llm(GroundedAnswer("x", emptyList(), emptyList())))
            runBlocking { strategy.run(state(notebookId = "")) }.status shouldBe KleioStatus.FAILED
        }
    })
