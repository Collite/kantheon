package org.tatrman.pinakes.pipeline

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.tatrman.pinakes.catalog.AssetRecord
import org.tatrman.pinakes.catalog.InMemoryAssetCatalog
import org.tatrman.pinakes.catalog.LineageStore
import org.tatrman.pinakes.clients.KallimachosWriteClient
import org.tatrman.pinakes.clients.LoadOutcome
import org.tatrman.pinakes.pipeline.stages.ChunkStage
import org.tatrman.pinakes.pipeline.stages.EmbedStage
import org.tatrman.pinakes.pipeline.stages.ExtractStage
import org.tatrman.pinakes.pipeline.stages.LoadStage
import org.tatrman.pinakes.stage.SeaweedAssetStore
import org.tatrman.pinakes.v1.StageKind

/**
 * P3 Stage 3.1 T4/T5/T6 — a full mechanical run through the library/runner
 * produces lineage `asset → run → source ids`, and the LineageStore merges
 * across runs.
 */
class LineageSpec :
    StringSpec({
        "the LineageStore merges runs + ids by asset" {
            val store = LineageStore()
            store.record("a1", "run-1", listOf(3))
            store.record("a1", "run-2", listOf(4), pageIds = listOf(42))
            val rec = store.get("a1")!!
            rec.runIds shouldContainExactly listOf("run-1", "run-2")
            rec.sourceIds shouldContainExactly listOf(3L, 4L)
            rec.pageIds shouldContainExactly listOf(42L)
        }

        "a mechanical run through the library lands a source + records lineage" {
            // Capturing write client — LOAD mints source 7 + parts; EMBED is a no-op.
            val writeClient =
                object : KallimachosWriteClient {
                    override suspend fun ensureNotebook(
                        notebookId: String,
                        displayName: String,
                    ) {}

                    override suspend fun loadSource(
                        notebookId: String,
                        title: String,
                        mimeType: String,
                        assetRef: String,
                        parts: List<String>,
                    ): LoadOutcome = LoadOutcome(sourceId = 7, partIds = parts.indices.map { it + 8L })

                    override suspend fun embedSource(sourceId: Long) {}
                }
            val assetStore = mockk<SeaweedAssetStore>()
            every { assetStore.get(any()) } returns "First paragraph.\n\nSecond paragraph.".toByteArray()

            val catalog = InMemoryAssetCatalog()
            catalog.record(AssetRecord("a1", "erp/a1-doc.txt", "erp", "text/plain", "doc.txt"))

            val embed = EmbedSpec("bge-m3", 1024, "1")
            val registry = PipelineRegistry(embed)
            registry.register(
                Pipeline(
                    "erp",
                    "ERP",
                    "erp",
                    listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD, StageKind.EMBED),
                    embed,
                ),
            )
            val library =
                StageLibrary(listOf(ExtractStage(), ChunkStage(), LoadStage(writeClient), EmbedStage(writeClient)))
            val lineageStore = LineageStore()
            val service = PipelineService(assetStore, catalog, registry, Runner(library), lineageStore)

            val run = runBlocking { service.run("erp", listOf("a1"), "run-1") }
            run.status.name shouldBe "SUCCEEDED"
            run.stages.map { it.kind } shouldContainExactly
                listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD, StageKind.EMBED)

            val lineage = service.lineage("a1")!!
            lineage.sourceIds shouldContainExactly listOf(7L)
            lineage.runIds shouldContainExactly listOf("run-1")
        }

        "resolvePipelineId falls back to the feed binding when id is blank" {
            val embed = EmbedSpec("bge-m3", 1024, "1")
            val registry = PipelineRegistry(embed)
            registry.register(Pipeline("erp", "ERP", "erp", listOf(StageKind.EXTRACT), embed))
            val catalog = InMemoryAssetCatalog()
            catalog.record(AssetRecord("a1", "erp/a1", "erp", "text/plain", "doc.txt"))
            val service = PipelineService(mockk(), catalog, registry, Runner(StageLibrary(emptyList())), LineageStore())
            service.resolvePipelineId("", "a1") shouldBe "erp"
        }
    })
