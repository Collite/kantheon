package org.tatrman.kantheon.hebe.memory.parity

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.tatrman.kantheon.hebe.api.MemoryScope
import org.tatrman.kantheon.hebe.api.MemoryStore
import org.tatrman.kantheon.hebe.memory.SqliteMemoryStore
import org.tatrman.kantheon.hebe.memory.db.DbFactory
import org.tatrman.kantheon.hebe.memory.embeddings.MockEmbeddingProvider
import org.tatrman.kantheon.hebe.memory.hygiene.HygieneScanner
import org.tatrman.kantheon.hebe.memory.search.Rrf
import org.tatrman.kantheon.hebe.memory.workspace.WorkspaceFs
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The **RRF parity gate** (Hebe P3 S3.1 T1). The golden corpus + `expected-rankings.json`
 * are the arbiter. Two unit-level guarantees:
 *
 *  1. The SQLite backend (the committed oracle) ranks each query's exact-term doc
 *     first — the tokenizer-stable part of the ranking.
 *  2. The fusion ([Rrf]) that both backends share is deterministic and produces the
 *     same ordering from the same candidate lists. Since the Postgres backend reuses
 *     [Rrf.fuse] verbatim and only its two candidate queries differ (architecture
 *     §5.2), fusion parity holds by construction here; cross-backend ranking parity
 *     against a **live** Postgres (tokenizer margin) is the integration suite
 *     (planning-conventions §4 — no Postgres in the unit suite).
 */
class RrfParityHarnessTest {
    @TempDir
    lateinit var tempDir: Path

    private val oracle: Json = Json { ignoreUnknownKeys = true }

    private data class Golden(
        val corpus: Map<String, String>,
        val expectedTop1: Map<String, String>,
    )

    private fun loadGolden(): Golden {
        val text =
            requireNotNull(javaClass.getResourceAsStream("/parity/expected-rankings.json")) {
                "missing /parity/expected-rankings.json"
            }.bufferedReader().readText()
        val root = oracle.parseToJsonElement(text).jsonObject
        val corpus = root["corpus"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val expected = root["expected_top1"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        return Golden(corpus, expected)
    }

    private fun sqliteStore(): MemoryStore {
        val db = DbFactory.open(tempDir.resolve("parity.db"))
        return SqliteMemoryStore(
            db = db,
            workspaceFs = WorkspaceFs(tempDir),
            embeddings = MockEmbeddingProvider(embeddingDim = 1536),
            hygieneScanner = HygieneScanner(),
            observer = null,
        )
    }

    @Test
    fun `golden corpus - sqlite oracle ranks the exact-term doc first`() =
        runBlocking {
            val golden = loadGolden()
            val store = sqliteStore()
            golden.corpus.forEach { (path, content) ->
                store.appendDoc(path, content, MemoryScope.Default, MemoryCategory.Document)
            }
            golden.expectedTop1.forEach { (query, expectedDoc) ->
                val hits = store.search(query, k = 5)
                assertTrue(hits.isNotEmpty(), "no hits for '$query'")
                assertEquals(expectedDoc, hits.first().docPath, "top-1 mismatch for '$query'")
            }
        }

    @Test
    fun `shared RRF fusion is deterministic and backend-independent`() {
        // The candidate lists a query produces (the only part that differs by backend).
        val fts =
            listOf(
                Rrf.RankedHit("rrf-fusion.md", 0, "reciprocal rank fusion"),
                Rrf.RankedHit("postgres-indexes.md", 0, "indexes"),
            )
        val vec =
            listOf(
                Rrf.RankedHit("rrf-fusion.md", 0, "reciprocal rank fusion"),
                Rrf.RankedHit("kotlin-coroutines.md", 0, "coroutines"),
            )
        // rrf-fusion appears in both lists at rank 0 -> highest fused score, deterministically first.
        val a = Rrf.fuse(fts, vec, k0 = 60, k = 10).map { it.docPath }
        val b = Rrf.fuse(fts, vec, k0 = 60, k = 10).map { it.docPath }
        assertEquals(a, b, "fusion must be deterministic")
        assertEquals("rrf-fusion.md", a.first(), "doc present in both candidate lists must fuse to the top")
    }
}
