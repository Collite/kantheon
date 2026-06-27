package org.tatrman.kallimachos.service

import org.tatrman.kallimachos.adapters.fulltext.FullTextPort
import org.tatrman.kallimachos.adapters.fulltext.FullTextQuery
import org.tatrman.kallimachos.adapters.notebook.NotebookPort
import org.tatrman.kallimachos.adapters.relational.RelationalPort
import org.tatrman.kallimachos.corpus.PageRecord
import org.tatrman.kallimachos.corpus.PartRecord
import org.tatrman.kallimachos.corpus.SourceRecord
import org.tatrman.kallimachos.model.MetadataValue

data class QuerySpec(
    val notebookId: String,
    val text: String? = null,
    val keywords: List<String> = emptyList(),
    val metadataFilter: Map<String, MetadataValue> = emptyMap(),
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 10
        const val ADMIN_SCOPE = "*"
    }
}

data class QueryHit(
    val id: Long,
    val kind: String,
    val score: Double,
    val snippet: String,
    val metadata: Map<String, MetadataValue> = emptyMap(),
)

/**
 * `getById` + the mart-scoped keyword/metadata `query` (contracts §1/§7). Every
 * query is scoped to `notebook_id`: there is NO un-scoped search at v1. The scope
 * resolves to the mart's member source ids; the admin `"*"` scope alone may
 * search the whole corpus (null filter). A query against a non-existent,
 * non-admin mart yields no hits (the mart simply contains nothing visible).
 */
class DocumentQueryService(
    private val relational: RelationalPort,
    private val fullText: FullTextPort,
    private val notebooks: NotebookPort,
) {
    fun getSource(id: Long): SourceRecord? = relational.getSource(id)

    /**
     * Defence-in-depth for the RLS edge: confirm a source belongs to the scoped
     * mart before returning it. The edge already authorized the caller for
     * `notebookId`; this stops a source id from another mart being read by id.
     * Admin scope `"*"` sees the whole corpus.
     */
    fun sourceInNotebook(
        notebookId: String,
        sourceId: Long,
    ): Boolean = notebookId == QuerySpec.ADMIN_SCOPE || sourceId in notebooks.memberSourceIds(notebookId)

    fun getSourceParts(id: Long): List<PartRecord> = relational.partsOfSource(id)

    fun getPart(id: Long): PartRecord? = relational.getPart(id)

    fun getPage(id: Long): PageRecord? = relational.getPage(id)

    fun query(spec: QuerySpec): List<QueryHit> {
        val allowedSourceIds: Set<Long>? =
            if (spec.notebookId == QuerySpec.ADMIN_SCOPE) {
                null // admin: whole corpus
            } else {
                notebooks.memberSourceIds(spec.notebookId)
            }
        // A non-admin mart with no members can match nothing — short-circuit.
        if (allowedSourceIds != null && allowedSourceIds.isEmpty()) return emptyList()

        return fullText
            .search(
                FullTextQuery(
                    text = spec.text,
                    keywords = spec.keywords,
                    metadataFilter = spec.metadataFilter,
                    limit = spec.limit,
                ),
                allowedSourceIds,
            ).map {
                QueryHit(
                    id = it.partId,
                    kind = "part",
                    score = it.score,
                    snippet = it.snippet,
                    metadata = it.metadata,
                )
            }
    }
}
