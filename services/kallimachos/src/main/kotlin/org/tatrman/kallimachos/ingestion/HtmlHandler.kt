package org.tatrman.kallimachos.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML → DocNode tree. Ported from doc-store `com.docstore.ingestion.htmlHandler`
 * (framework-agnostic; package + `AppConfig` → [IngestionConfig] are the only
 * changes). Headings create hierarchy; lists are flattened with two-space
 * indentation and kept as single blocks; code blocks are kept intact.
 */
fun extractPlainTextFromHtml(
    html: String,
    config: IngestionConfig,
): String = parseHtmlToTree(html, config).text

fun parseHtmlToTree(
    html: String,
    @Suppress("UNUSED_PARAMETER") config: IngestionConfig,
    fileName: String? = null,
): DocNode {
    val doc = Jsoup.parse(html)

    val title = doc.title().takeIf { it.isNotBlank() } ?: fileName?.substringBeforeLast('.') ?: "Untitled"

    val root =
        DocNode(
            id = 0,
            level = 0,
            parentIndex = 0,
            title = title,
            text = "",
            ownText = "",
            children = mutableListOf(),
            type = DocNodeType.DOC,
            theHtml = doc.body(),
        )

    root.children.addAll(
        doc
            .body()
            .childNodes()
            .withIndex()
            .mapNotNull { processNode(it.value, it.index) },
    )

    postprocessDocNode(root)
    renumberDocNode(root, 0)
    combineText(root)
    setTitle(root, title)

    return root
}

private fun processNode(
    node: Node,
    i: Int,
): DocNode? {
    val children =
        if (node is Element) {
            node.childNodes().withIndex().mapNotNull { processNode(it.value, it.index) }
        } else {
            emptyList()
        }

    when (node) {
        is Element ->
            when {
                node.tagName().equals("ul", true) -> {
                    val items = extractListItems(node, ordered = false)
                    val listText = items.items.joinToString("\n")
                    return docNode(listText, index = i, type = DocNodeType.LIST, html = node)
                }

                node.tagName().equals("ol", true) -> {
                    val items = extractListItems(node, ordered = true)
                    val listText = items.items.joinToString("\n")
                    return docNode(listText, index = i, type = DocNodeType.LIST, html = node)
                }

                node.tagName().equals("pre", true) || node.tagName().equals("code", true) -> {
                    val code = node.wholeText().trimEnd('\n')
                    return if (code.isNotBlank()) {
                        docNode(code, index = i, type = DocNodeType.CODE, html = node)
                    } else {
                        null
                    }
                }

                node.tagName().equals("div", true) -> {
                    val t = blockText(node)
                    return if (t.isNotBlank()) {
                        docNode(t, index = i, type = DocNodeType.PARA, html = node)
                    } else {
                        null
                    }
                }

                node.tagName().matches(Regex("h[1-7]", RegexOption.IGNORE_CASE)) -> {
                    val level =
                        node
                            .tagName()
                            .substring(1)
                            .toInt()
                            .coerceIn(1, 7)
                    val title = normalizeInline(node.text())
                    val type = DocNodeType.fromLevel(level)
                    val ownText = node.wholeOwnText()
                    val wholeText = node.wholeText()

                    val me = docNode(ownText, wholeText, title, level, i, type, node)
                    me.children.addAll(children)
                    return me
                }

                node.tagName().equals("p", true) -> {
                    // HACK: treat single-line bold paragraphs as a heading at (parent.level + 1)
                    val title = detectPoorMansHeadings(node)

                    if (title.isNotBlank()) {
                        val type = DocNodeType.Hx
                        val ownText = node.wholeOwnText()
                        val wholeText = ""
                        return docNode(ownText, wholeText, title, index = i, type = type, html = node)
                    } else {
                        val t = blockText(node)
                        return if (t.isNotBlank()) {
                            docNode(t, index = i, type = DocNodeType.PARA, html = node)
                        } else {
                            null
                        }
                    }
                }

                else -> {
                    val t = blockText(node)
                    return if (t.isNotBlank()) {
                        docNode(t, index = i, type = DocNodeType.PARA, html = node)
                    } else {
                        null
                    }
                }
            }

        is TextNode -> {
            val t = normalizeInline(node.text())
            return if (t.isNotBlank()) {
                docNode(t, index = i, type = DocNodeType.PARA, html = node)
            } else {
                null
            }
        }

        else -> return null
    }
}

private fun postprocessDocNode(docNode: DocNode) {
    var currentParent: DocNode? = null
    var parentLevel = docNode.level
    val toRemove = mutableListOf<DocNode>()
    docNode.children.forEach {
        if (it.type == DocNodeType.Hx) {
            currentParent = it
            parentLevel = docNode.level + 1
        } else {
            val cp = currentParent
            if (cp != null) {
                cp.children.add(it)
                toRemove.add(it)
            }
        }
        if (it.level == -1) {
            it.level = parentLevel + 1
        }
        postprocessDocNode(it)
    }
    docNode.children.removeAll(toRemove)
}

private fun renumberDocNode(
    docNode: DocNode,
    level: Int,
) {
    docNode.level = level
    docNode.children.forEachIndexed { i, c ->
        renumberDocNode(c, level + 1)
        c.parentIndex = i
    }
}

private fun combineText(docNode: DocNode) {
    docNode.children.forEach { combineText(it) }

    if (docNode.text.isBlank() && docNode.children.isNotEmpty()) {
        docNode.text = docNode.children.joinToString("\n\n") { it.text }
    }
}

private fun setTitle(
    docNode: DocNode,
    title: String,
) {
    if (docNode.title.isNullOrBlank()) {
        docNode.title = title
    }
    docNode.children.forEachIndexed { i, c ->
        setTitle(c, "${docNode.title}: part $i")
    }
}

private fun docNode(
    ownText: String,
    wholeText: String? = null,
    title: String? = null,
    level: Int = -1,
    index: Int = 0,
    type: DocNodeType,
    html: Node,
): DocNode =
    DocNode(
        id = 0,
        level = level,
        parentIndex = index,
        title = title,
        text = wholeText ?: ownText,
        ownText = ownText,
        children = mutableListOf(),
        type = type,
        theHtml = html,
    )

private data class ListExtraction(
    val items: List<String>,
    val indent: Int,
)

private fun extractListItems(
    listEl: Element,
    ordered: Boolean,
    indent: Int = 0,
): ListExtraction {
    val items = mutableListOf<String>()
    var index = 1
    for (li in listEl.children()) {
        if (!li.tagName().equals("li", true)) continue
        val innerText = blockText(li)
        val prefix = if (ordered) "$index." else "-"
        val line = (" ".repeat(indent * 2) + "$prefix " + normalizeInline(innerText)).trimEnd()
        items += line
        li.children().forEach { ch ->
            if (ch.tagName().equals("ul", true) || ch.tagName().equals("ol", true)) {
                val nested = extractListItems(ch, ordered = ch.tagName().equals("ol", true), indent = indent + 1)
                items += nested.items
            }
        }
        index++
    }
    return ListExtraction(items, indent)
}

private fun blockText(el: Element): String {
    val sb = StringBuilder()

    fun walk(n: Node) {
        when (n) {
            is TextNode -> sb.append(normalizeInline(n.text()))
            is Element -> {
                if (n.tagName().equals("br", true)) {
                    sb.append("\n")
                } else {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
                    n.childNodes().forEach { walk(it) }
                }
            }
        }
    }
    el.childNodes().forEach { walk(it) }
    return normalizeBlock(sb.toString())
}

private fun normalizeInline(s: String): String =
    s
        .replace("\u00A0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeBlock(s: String): String {
    val lines = s.replace("\r", "").split("\n")
    val norm = lines.joinToString("\n") { it.trim() }
    return norm.replace(Regex("\n{3,}"), "\n\n").trim()
}

// returns a string heading if the node is assumed to be a heading; "" otherwise
private fun detectPoorMansHeadings(node: Element): String {
    var boldEl: Element? = null
    for (cn in node.childNodes()) {
        when (cn) {
            is TextNode -> if (cn.text().trim().isNotEmpty()) return ""
            is Element -> {
                val tn = cn.tagName().lowercase()
                if (tn == "b" || tn == "strong") {
                    if (boldEl != null) return "" // more than one element
                    boldEl = cn
                } else {
                    return ""
                }
            }

            else -> return ""
        }
    }
    val be = boldEl ?: return ""
    val headingTitle = normalizeInline(be.text())
    if (headingTitle.isBlank()) return ""
    if (headingTitle.contains('\n') || headingTitle.contains('\r')) return ""
    return headingTitle
}
