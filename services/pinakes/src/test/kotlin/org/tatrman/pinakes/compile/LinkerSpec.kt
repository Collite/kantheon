package org.tatrman.pinakes.compile

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.v1.EdgeKind
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.resolve.ResolveOutcome
import org.tatrman.pinakes.resolve.ResolvedPage

/**
 * P3 Stage 3.2 T3 — the LINK stage's content edges. Page→source `DERIVED_FROM`
 * rides each draft's `derivedFromParts` (materialised at write); the Linker
 * builds the page↔page content edges the graph-primary walk follows.
 */
class LinkerSpec :
    StringSpec({
        fun resolved(
            localId: Int,
            kind: PageKind,
        ) = ResolvedPage(PageDraft(localId, kind, "p$localId", "...", listOf(1)), ResolveOutcome.NEW)

        "SUMMARY MENTIONS each entity/concept; entities are RELATED to one another" {
            val pages =
                listOf(
                    resolved(0, PageKind.SUMMARY),
                    resolved(1, PageKind.ENTITY),
                    resolved(2, PageKind.CONCEPT),
                )
            val edges = Linker().link(pages)

            // SUMMARY(0) → ENTITY(1), SUMMARY(0) → CONCEPT(2): MENTIONS.
            edges
                .filter {
                    it.kind == EdgeKind.MENTIONS
                }.map { it.fromLocalId to it.toLocalId } shouldContainExactlyInAnyOrder
                listOf(0 to 1, 0 to 2)
            // ENTITY(1) ↔ CONCEPT(2): RELATED.
            edges
                .filter {
                    it.kind == EdgeKind.RELATED
                }.map { it.fromLocalId to it.toLocalId } shouldContainExactlyInAnyOrder
                listOf(1 to 2)
        }

        "a lone SUMMARY produces no content edges" {
            Linker().link(listOf(resolved(0, PageKind.SUMMARY))).size shouldBe 0
        }
    })
