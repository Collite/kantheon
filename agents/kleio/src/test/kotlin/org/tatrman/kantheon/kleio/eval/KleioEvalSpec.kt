package org.tatrman.kantheon.kleio.eval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.kleio.clients.GroundedAnswer
import org.tatrman.kantheon.kleio.clients.KallimachosMcpClient
import org.tatrman.kantheon.kleio.clients.KleioLlmClient
import org.tatrman.kantheon.kleio.clients.RetrievedChunk
import org.tatrman.kantheon.kleio.graph.KleioStatus
import org.tatrman.kantheon.kleio.graph.KleioStrategy
import org.tatrman.kantheon.kleio.graph.KleioTurnState

/**
 * P5 Stage 5.3 T2 — the eval gate (eval/README.md): grounded-citation
 * faithfulness, NO_GROUNDING honesty, mart-scope-leakage negatives. Mock-driven;
 * the live faithfulness eval is the integration suite.
 */
class KleioEvalSpec :
    StringSpec({
        // An eval case: what the (RLS-scoped) retriever returns + what the model claims to cite.
        data class Case(
            val name: String,
            val retrieved: List<RetrievedChunk>,
            val answer: GroundedAnswer,
            val expectStatus: KleioStatus,
        )

        fun c(
            partId: Long,
            sourceId: Long = 3,
        ) = RetrievedChunk(
            partId,
            sourceId,
            null,
            "t$partId",
            0.9,
            "Doc",
            "¶$partId",
            "kallimachos://nbA/$sourceId/$partId",
        )

        val corpus =
            listOf(
                Case(
                    "faithful: cites only retrieved",
                    listOf(c(11), c(12)),
                    GroundedAnswer("a [11][12]", citedPartIds = listOf(11, 12), citedPageIds = emptyList()),
                    KleioStatus.DONE,
                ),
                Case(
                    "leakage: model tries to cite an out-of-mart id (42) — must be dropped",
                    listOf(c(11)),
                    GroundedAnswer("a", citedPartIds = listOf(11, 42), citedPageIds = emptyList()),
                    KleioStatus.DONE,
                ),
                Case(
                    "honesty: nothing retrieved → NO_GROUNDING, no fabricated citations",
                    emptyList(),
                    GroundedAnswer(
                        "the model would have invented this",
                        citedPartIds = listOf(99),
                        citedPageIds = emptyList(),
                    ),
                    KleioStatus.NO_GROUNDING,
                ),
            )

        corpus.forEach { case ->
            "eval — ${case.name}" {
                val retriever =
                    object : KallimachosMcpClient {
                        override suspend fun getContext(
                            notebookId: String,
                            question: String,
                            k: Int,
                            bearer: String?,
                        ): List<RetrievedChunk> = case.retrieved
                    }
                val llm =
                    object : KleioLlmClient {
                        override suspend fun answer(
                            question: String,
                            chunks: List<RetrievedChunk>,
                        ): GroundedAnswer = case.answer
                    }
                val out =
                    runBlocking {
                        KleioStrategy(
                            retriever,
                            llm,
                        ).run(KleioTurnState("t", "q", "nbA", "bearer", k = 8, minScore = 0.0))
                    }

                out.status shouldBe case.expectStatus
                // FAITHFULNESS + NO-LEAKAGE: every cited source was actually retrieved in THIS mart.
                val retrievedIds = case.retrieved.map { it.partId }.toSet()
                out.sourcesUsed.all { it.partId in retrievedIds } shouldBe true
                // HONESTY: a NO_GROUNDING turn fabricates nothing.
                if (case.expectStatus == KleioStatus.NO_GROUNDING) out.sourcesUsed.isEmpty() shouldBe true
            }
        }
    })
