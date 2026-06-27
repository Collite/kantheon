package org.tatrman.kantheon.midas.core.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Stage 1.4 T6 — the five tool manifests load into `ToolCapability` protos with the
 * contract `capability_id`s + search tags (contracts.md §3.6), so capabilities-mcp
 * lists `midas.*:v1`. Reads the real YAMLs from the main resources on the test
 * classpath.
 */
class ManifestLoaderSpec :
    StringSpec({

        "loads the five midas.*:v1 tool capabilities" {
            val caps = ManifestLoader().loadAll()
            caps.map { it.tool.capabilityId } shouldContainExactlyInAnyOrder
                listOf(
                    "midas.position.valuation:v1",
                    "midas.portfolio.performance:v1",
                    "midas.position.cost_basis:v1",
                    "midas.transaction.fee_allocation:v1",
                    "midas.reconcile.statement:v1",
                )
        }

        "each capability carries category, version, endpoint, and search tags" {
            val caps = ManifestLoader().loadAll().associateBy { it.tool.capabilityId }
            val valuation = caps.getValue("midas.position.valuation:v1").tool
            valuation.category shouldBe "midas"
            valuation.version shouldBe "1.0.0"
            valuation.serviceEndpoint shouldBe "http://midas-core:7311"
            valuation.searchTagsList shouldContainAll listOf("position", "valuation", "nav")
            valuation.costHints.isIdempotent shouldBe true
        }
    })
