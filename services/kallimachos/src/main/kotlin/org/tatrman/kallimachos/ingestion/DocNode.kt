package org.tatrman.kallimachos.ingestion

import org.jsoup.nodes.Node

/**
 * Document node tree representation used for documents and all parts.
 *
 * Ported from doc-store `com.docstore.ingestion.model` (framework-agnostic;
 * the only change is the package). The `theHtml` jsoup back-reference is kept
 * for the HTML handler's structural walk.
 */
enum class DocNodeType {
    DOC,
    H1,
    H2,
    H3,
    H4,
    H5,
    H6,
    H7,
    Hx,
    LIST,
    CODE,
    QUESTION,
    PAGE,
    PARA,
    OTHER,
    ;

    companion object {
        fun fromLevel(level: Int): DocNodeType =
            when (level) {
                1 -> H1
                2 -> H2
                3 -> H3
                4 -> H4
                5 -> H5
                6 -> H6
                else -> H7
            }
    }
}

data class DocNode(
    var id: Long = 0L, // DB-assigned id (0 when not yet persisted)
    var level: Int, // 0 for root
    var parentIndex: Int, // order within parent (0-based). Root is 0.
    var title: String?, // heading/title when applicable
    var text: String, // whole text including children
    var ownText: String, // node's own text without children
    val children: MutableList<DocNode>,
    val type: DocNodeType,
    val theHtml: Node? = null,
)
