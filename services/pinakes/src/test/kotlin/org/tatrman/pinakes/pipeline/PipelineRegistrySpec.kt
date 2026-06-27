package org.tatrman.pinakes.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.pinakes.v1.StageKind

/**
 * P3 Stage 3.1 T2/T3 — the registry: per-source-feed binding + the conformed
 * embedding-dimension check (architecture §11 — disagreement = two corpora).
 */
class PipelineRegistrySpec :
    StringSpec({
        val corpus = EmbedSpec("bge-m3", 1024, "1")

        fun pipeline(
            id: String,
            feed: String,
            embed: EmbedSpec,
        ) = Pipeline(id, id, feed, listOf(StageKind.EXTRACT, StageKind.CHUNK, StageKind.LOAD), embed)

        "a pipeline with a matching EmbedConfig registers + binds to its feed" {
            val registry = PipelineRegistry(corpus)
            registry.register(pipeline("erp", "erp", corpus))
            registry.forFeed("erp")?.id shouldBe "erp"
            registry.get("erp")?.sourceFeed shouldBe "erp"
        }

        "a pipeline whose EmbedConfig disagrees with the corpus is rejected at registration" {
            val registry = PipelineRegistry(corpus)
            shouldThrow<EmbedConformanceException> {
                registry.register(pipeline("bad", "sp", EmbedSpec("other-model", 768, "1")))
            }
            // a dimension-only disagreement also fails (the conformed dimension)
            shouldThrow<EmbedConformanceException> {
                registry.register(pipeline("bad2", "sp", EmbedSpec("bge-m3", 512, "1")))
            }
        }

        "YAML pipeline defs parse to bound pipelines" {
            val yaml =
                """
                pipelines:
                  - id: erp-export
                    displayName: "ERP export"
                    sourceFeed: erp
                    stages: [EXTRACT, CHUNK, EMBED, COMPILE, LINK, RESOLVE, LOAD]
                    embed:
                      modelId: bge-m3
                      dimensions: 1024
                      modelVersion: "1"
                """.trimIndent()
            val defs = PipelineDefs.fromYaml(yaml)
            defs.size shouldBe 1
            defs.first().sourceFeed shouldBe "erp"
            defs.first().stages shouldBe
                listOf(
                    StageKind.EXTRACT,
                    StageKind.CHUNK,
                    StageKind.EMBED,
                    StageKind.COMPILE,
                    StageKind.LINK,
                    StageKind.RESOLVE,
                    StageKind.LOAD,
                )
            defs.first().embed shouldBe EmbedSpec("bge-m3", 1024, "1")
        }
    })
