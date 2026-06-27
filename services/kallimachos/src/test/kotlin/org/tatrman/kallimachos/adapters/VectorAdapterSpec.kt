package org.tatrman.kallimachos.adapters

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.adapters.vector.InMemoryVectorAdapter
import org.tatrman.kallimachos.adapters.vector.PartVectorRecord

/**
 * P2 Stage 2.1 T4 — the vector port contract on the in-memory adapter: idempotent
 * upsert keyed by `(part, model, version)`, cosine KNN order, mart-scope filter.
 * Real pgvector recall is the integration suite.
 */
class VectorAdapterSpec :
    StringSpec({
        fun vec(
            partId: Long,
            sourceId: Long,
            v: FloatArray,
            version: String = "1",
        ) = PartVectorRecord(partId, sourceId, v, "bge-m3", version)

        "upsert is idempotent by (part, model, version)" {
            val a = InMemoryVectorAdapter()
            a.upsert(listOf(vec(1, 10, floatArrayOf(1f, 0f, 0f))))
            a.upsert(listOf(vec(1, 10, floatArrayOf(0f, 1f, 0f)))) // overwrites same key
            val hits = a.knn(floatArrayOf(0f, 1f, 0f), 10, null)
            hits.size shouldBe 1
            hits.first().partId shouldBe 1L
        }

        "a new model version coexists with the old vector" {
            val a = InMemoryVectorAdapter()
            a.upsert(listOf(vec(1, 10, floatArrayOf(1f, 0f, 0f), version = "1")))
            a.upsert(listOf(vec(1, 10, floatArrayOf(0f, 1f, 0f), version = "2")))
            a.knn(floatArrayOf(1f, 0f, 0f), 10, null).size shouldBe 2
        }

        "KNN ranks by cosine similarity, closest first" {
            val a = InMemoryVectorAdapter()
            a.upsert(
                listOf(
                    vec(1, 10, floatArrayOf(1f, 0f, 0f)),
                    vec(2, 20, floatArrayOf(0f, 1f, 0f)),
                    vec(3, 30, floatArrayOf(0.9f, 0.1f, 0f)),
                ),
            )
            a.knn(floatArrayOf(1f, 0f, 0f), 2, null).map { it.partId } shouldContainExactly listOf(1L, 3L)
        }

        "KNN filters to the mart's member sources" {
            val a = InMemoryVectorAdapter()
            a.upsert(
                listOf(
                    vec(1, 10, floatArrayOf(1f, 0f, 0f)),
                    vec(2, 20, floatArrayOf(1f, 0f, 0f)),
                ),
            )
            a.knn(floatArrayOf(1f, 0f, 0f), 10, setOf(20L)).map { it.sourceId } shouldContainExactly listOf(20L)
        }
    })
