package org.tatrman.pinakes.catalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * P1 Stage 1.3 T3 — the asset catalogue: record round-trip + feed binding.
 */
class AssetCatalogSpec :
    StringSpec({
        "record round-trips and binds the feed" {
            val catalog = InMemoryAssetCatalog()
            val rec = catalog.record(AssetRecord("a1", "erp/a1-doc.txt", "erp", "text/plain", "doc.txt"))
            rec.sourceFeed shouldBe "erp"
            catalog.get("a1")!!.assetRef shouldBe "erp/a1-doc.txt"
        }

        "list filters by feed" {
            val catalog = InMemoryAssetCatalog()
            catalog.record(AssetRecord("a1", "erp/a1", "erp", "text/plain", "a"))
            catalog.record(AssetRecord("a2", "erp/a2", "erp", "text/plain", "b"))
            catalog.record(AssetRecord("a3", "sp/a3", "sharepoint", "text/plain", "c"))

            catalog.list("erp").map { it.id } shouldContainExactlyInAnyOrder listOf("a1", "a2")
            catalog.list().map { it.id } shouldContainExactlyInAnyOrder listOf("a1", "a2", "a3")
        }

        "get of an unknown asset is null" {
            InMemoryAssetCatalog().get("nope").shouldBeNull()
        }
    })
