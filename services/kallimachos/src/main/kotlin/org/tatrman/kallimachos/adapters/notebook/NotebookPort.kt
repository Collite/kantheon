package org.tatrman.kallimachos.adapters.notebook

import org.tatrman.kallimachos.corpus.NodeKind
import org.tatrman.kallimachos.corpus.NotebookRecord

/**
 * The notebooks (marts) plane (contracts §3 — `notebooks` + `notebook_members`).
 * A notebook is an m:n curated subset of the corpus; membership is what mart-
 * scoped retrieval filters against ([memberSourceIds]). Owner + `visibility_roles`
 * are stored at v1; OBO/Validate enforcement is P4.
 */
interface NotebookPort {
    fun create(notebook: NewNotebook): NotebookRecord

    fun get(id: String): NotebookRecord?

    fun list(): List<NotebookRecord>

    fun addMember(
        notebookId: String,
        nodeKind: NodeKind,
        nodeId: Long,
    )

    /** Source ids that are members of the mart — the keyword `query` scope. */
    fun memberSourceIds(notebookId: String): Set<Long>

    fun memberCount(notebookId: String): Long
}

data class NewNotebook(
    val id: String,
    val displayName: String,
    val ownerUserId: String,
    val visibilityRoles: List<String> = emptyList(),
)
