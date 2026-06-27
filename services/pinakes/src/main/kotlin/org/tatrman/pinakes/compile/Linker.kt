package org.tatrman.pinakes.compile

import org.tatrman.kallimachos.v1.EdgeKind

/**
 * The LINK stage's edge derivation (architecture §6 — the content links are what
 * graph-primary retrieval walks). Page→source `DERIVED_FROM` provenance is
 * carried by each draft's `derivedFromParts` (materialised at write); this builds
 * the page↔page CONTENT edges: a SUMMARY `MENTIONS` each entity/concept page it
 * was compiled alongside; entity/concept pages are `RELATED` to one another
 * within a source. (The LLM may refine these in later versions; v1 is structural.)
 */
class Linker {
    fun link(resolved: List<org.tatrman.pinakes.resolve.ResolvedPage>): List<EdgeDraft> {
        val pages = resolved.map { it.draft }
        val summaries = pages.filter { it.kind == org.tatrman.kallimachos.v1.PageKind.SUMMARY }
        val concepts =
            pages.filter {
                it.kind == org.tatrman.kallimachos.v1.PageKind.ENTITY ||
                    it.kind == org.tatrman.kallimachos.v1.PageKind.CONCEPT
            }

        val edges = mutableListOf<EdgeDraft>()
        // SUMMARY → each entity/concept: MENTIONS.
        for (s in summaries) {
            for (c in concepts) edges += EdgeDraft(s.localId, c.localId, EdgeKind.MENTIONS)
        }
        // entity/concept ↔ entity/concept within the source: RELATED (undirected, emit one way).
        for (i in concepts.indices) {
            for (j in i + 1 until concepts.size) {
                edges += EdgeDraft(concepts[i].localId, concepts[j].localId, EdgeKind.RELATED)
            }
        }
        return edges
    }
}
