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
import org.tatrman.kantheon.kleio.v1.ArtifactKind

/**
 * P5 Stage 5.2 T1 — artifact generation map-reduce over a mart → a cited
 * MARKDOWN envelope; the grounding contract holds (cite only retrieved); an empty
 * mart yields a non-grounded "not enough sources" artifact.
 */
class ArtifactNodeSpec :
    StringSpec({
        fun chunk(id: Long) = RetrievedChunk(id, 3, null, "text $id", 0.9, "Doc3", "¶$id", "kallimachos://nb/3/$id")

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

        "a SUMMARY artifact maps-reduces the mart into a cited markdown envelope" {
            val node =
                ArtifactNode(
                    retriever(listOf(chunk(11), chunk(12))),
                    llm(GroundedAnswer("# Summary\n...", listOf(11, 12), emptyList())),
                )
            val out = runBlocking { node.generate("nb", ArtifactKind.SUMMARY, focus = null, bearer = "t") }

            out.grounded shouldBe true
            out.kind shouldBe ArtifactKind.SUMMARY
            out.sourcesUsed.map { it.partId } shouldContainExactlyInAnyOrder listOf(11L, 12L)
            out.envelope.text shouldContain "Summary"
        }

        "hallucinated citations are dropped in artifacts too" {
            val node =
                ArtifactNode(retriever(listOf(chunk(11))), llm(GroundedAnswer("...", listOf(11, 999), emptyList())))
            runBlocking {
                node.generate("nb", ArtifactKind.FAQ, null, "t")
            }.sourcesUsed.map { it.partId } shouldContainExactlyInAnyOrder
                listOf(11L)
        }

        "an empty mart yields a non-grounded 'not enough sources' artifact" {
            val node = ArtifactNode(retriever(emptyList()), llm(GroundedAnswer("x", emptyList(), emptyList())))
            val out = runBlocking { node.generate("nb", ArtifactKind.TIMELINE, null, "t") }
            out.grounded shouldBe false
            out.sourcesUsed.isEmpty() shouldBe true
            out.envelope.text shouldContain "Not enough sources"
        }
    })
