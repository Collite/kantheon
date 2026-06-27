package org.tatrman.pinakes.compile

import org.tatrman.kallimachos.v1.EdgeKind
import org.tatrman.kallimachos.v1.PageKind

/** A part the compiler reasons over (the loaded source's chunks, id + text). */
data class PartInput(
    val id: Long,
    val text: String,
)

/**
 * A draft wiki page (pre-write). `localId` is a within-run handle the LINK stage
 * uses to wire page↔page edges before the LoadApi assigns real ids. `conceptRef`
 * is the §6 Ariadne seam — wiki-local at v1 (`ariadneQname` empty).
 */
data class PageDraft(
    val localId: Int,
    val kind: PageKind,
    val title: String,
    val contentMd: String,
    val derivedFromParts: List<Long>,
    val conceptRef: ConceptRefDraft? = null,
)

data class ConceptRefDraft(
    val entityType: String,
    val entityId: String,
    val displayLabel: String,
    val ariadneQname: String = "",
)

/** A page↔page / page↔entity content edge, referencing pages by `localId`. */
data class EdgeDraft(
    val fromLocalId: Int,
    val toLocalId: Int,
    val kind: EdgeKind,
)
