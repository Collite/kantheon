package org.tatrman.kallimachos.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kallimachos.adapters.relational.InMemoryRelationalAdapter
import org.tatrman.kallimachos.adapters.relational.NewPart
import org.tatrman.kallimachos.adapters.relational.NewSource
import org.tatrman.kallimachos.adapters.vector.InMemoryVectorAdapter
import org.tatrman.kallimachos.corpus.EmbeddingStatus
import org.tatrman.kallimachos.embeddings.EmbedResult
import org.tatrman.kallimachos.embeddings.EmbeddingsPort
import org.tatrman.kallimachos.retrieval.VectorRecall
import org.tatrman.kallimachos.tx.SnapshotTransactor

/**
 * P2 Stage 2.1 T3/T6 — the EMBED operation + backfill of the non-atomic
 * embedding edge. A successful embed writes vectors + flips the source to OK;
 * an embed failure leaves it PENDING for the backfill (never a hard error).
 */
class EmbeddingServiceSpec :
    StringSpec({
        // A fake embeddings egress: returns a fixed 3-dim vector per text, or throws.
        class FakeEmbeddings(
            val failing: Boolean = false,
        ) : EmbeddingsPort {
            override suspend fun embed(texts: List<String>): EmbedResult {
                if (failing) throw RuntimeException("llm-gateway down")
                return EmbedResult(texts.map { floatArrayOf(1f, 0f, 0f) }, "bge-m3", "1", 3)
            }
        }

        fun seed(relational: InMemoryRelationalAdapter): Long {
            val s = relational.insertSource(NewSource(title = "Doc")) // status PENDING by default
            relational.insertParts(s.id, listOf(NewPart(0, "paragraph", "alpha"), NewPart(1, "paragraph", "beta")))
            return s.id
        }

        "embedSource writes vectors and flips the source to OK" {
            val relational = InMemoryRelationalAdapter()
            val vector = InMemoryVectorAdapter()
            val service = EmbeddingService(relational, vector, FakeEmbeddings(), SnapshotTransactor(relational))
            val id = seed(relational)

            runBlocking { service.embedSource(id) } shouldBe true
            relational.getSource(id)!!.embeddingStatus shouldBe EmbeddingStatus.OK
            vector.knn(floatArrayOf(1f, 0f, 0f), 10, null).size shouldBe 2 // both parts embedded
        }

        "an embed failure leaves the source PENDING (non-atomic edge)" {
            val relational = InMemoryRelationalAdapter()
            val vector = InMemoryVectorAdapter()
            val service =
                EmbeddingService(relational, vector, FakeEmbeddings(failing = true), SnapshotTransactor(relational))
            val id = seed(relational)

            runBlocking { service.embedSource(id) } shouldBe false
            relational.getSource(id)!!.embeddingStatus shouldBe EmbeddingStatus.PENDING
            vector.knn(floatArrayOf(1f, 0f, 0f), 10, null).size shouldBe 0
        }

        "backfillEmbeddings embeds all PENDING sources" {
            val relational = InMemoryRelationalAdapter()
            val vector = InMemoryVectorAdapter()
            val service = EmbeddingService(relational, vector, FakeEmbeddings(), SnapshotTransactor(relational))
            seed(relational)
            seed(relational)

            runBlocking { service.backfillEmbeddings() } shouldBe 2
            relational.pendingEmbeddingSourceIds(10).size shouldBe 0
        }

        "VectorRecall embeds the query and returns KNN candidates" {
            val vector = InMemoryVectorAdapter()
            val relational = InMemoryRelationalAdapter()
            val service = EmbeddingService(relational, vector, FakeEmbeddings(), SnapshotTransactor(relational))
            val id = seed(relational)
            runBlocking { service.embedSource(id) }

            val recall = VectorRecall(vector, FakeEmbeddings())
            runBlocking { recall.recall("alpha", 10, null) }.isNotEmpty() shouldBe true
        }
    })
