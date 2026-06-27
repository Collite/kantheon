package org.tatrman.pinakes.resolve

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.compile.ConceptRefDraft
import org.tatrman.pinakes.compile.PageDraft

/**
 * P3 Stage 3.2 T5 — global entity resolution (architecture §7): resolution
 * against the WHOLE corpus so "Kaufland" is one node across feeds; new vs merged;
 * `concept_ref` wiki-local (`ariadne_qname` empty — the §6/§12 seam).
 */
class EntityResolverSpec :
    StringSpec({
        fun entity(
            localId: Int,
            label: String,
        ) = PageDraft(
            localId,
            PageKind.ENTITY,
            label,
            "# $label",
            listOf(1),
            ConceptRefDraft("customer", "wiki:${label.lowercase()}", label),
        )

        "Kaufland across two feeds resolves to ONE node (new then merged)" {
            val index = InMemoryConceptIndex()
            val resolver = EntityResolver(index)

            val feed1 = resolver.resolve(listOf(entity(0, "Kaufland")))
            feed1.first().outcome shouldBe ResolveOutcome.NEW

            // A second feed mentions the same entity — global resolution merges it.
            val feed2 = resolver.resolve(listOf(entity(0, "Kaufland")))
            feed2.first().outcome shouldBe ResolveOutcome.MERGED
            feed2
                .first()
                .draft.conceptRef!!
                .entityId shouldBe "wiki:kaufland"
            feed2
                .first()
                .draft.conceptRef!!
                .ariadneQname shouldBe "" // not yet bridged (§12)
        }

        "non-entity pages (SUMMARY) are always new" {
            val resolver = EntityResolver(InMemoryConceptIndex())
            val summary = PageDraft(0, PageKind.SUMMARY, "Overview", "...", listOf(1), conceptRef = null)
            resolver.resolve(listOf(summary)).first().outcome shouldBe ResolveOutcome.NEW
        }

        "distinct entities each resolve as new" {
            val resolver = EntityResolver(InMemoryConceptIndex())
            val out = resolver.resolve(listOf(entity(0, "Kaufland"), entity(1, "Lidl")))
            out.all { it.outcome == ResolveOutcome.NEW } shouldBe true
        }
    })
