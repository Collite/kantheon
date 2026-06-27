package org.tatrman.kallimachos.retrieval

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * P2 Stage 2.3 T3 — the citation ↔ envelope mapping (contracts §5), the grounding
 * contract Kleio (P5) renders cited blocks against:
 *  - `source_ref` → `Block.provenance.source_tables[]`
 *  - ids → `Drilldown.arg_mapping {sourceId, partId, pageId}`, scope=point, source=citation
 *  - title + locator → `Drilldown.display`
 */
class CitationMappingSpec :
    StringSpec({
        val citation =
            Citation(
                sourceId = 3,
                partId = 11,
                pageId = null,
                title = "Kaufland",
                locator = "¶12",
                sourceRef = "kallimachos://nb1/3/11",
            )

        "source_ref maps to Block.provenance.source_tables + the producing agent" {
            val prov = Citations.toProvenance(citation, computedAt = "2026-06-26T00:00:00Z")
            prov.producingAgentId shouldBe "kleio"
            prov.sourceTablesList shouldBe listOf("kallimachos://nb1/3/11")
            prov.computedAt shouldBe "2026-06-26T00:00:00Z"
        }

        "ids map to Drilldown.arg_mapping with scope=point, source=citation" {
            val d = Citations.toDrilldown(citation)
            d.scope shouldBe "point"
            d.source shouldBe "citation"
            d.argMappingMap["sourceId"] shouldBe "3"
            d.argMappingMap["partId"] shouldBe "11"
            d.argMappingMap.containsKey("pageId") shouldBe false // null at P2
            d.display shouldBe "Kaufland — ¶12"
        }

        "a page-bearing citation carries pageId in the drilldown args" {
            val d = Citations.toDrilldown(citation.copy(pageId = 42))
            d.argMappingMap["pageId"] shouldBe "42"
        }
    })
