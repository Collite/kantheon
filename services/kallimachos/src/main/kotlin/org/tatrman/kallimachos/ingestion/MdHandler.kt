package org.tatrman.kallimachos.ingestion

import com.vladsch.flexmark.ast.BulletList
import com.vladsch.flexmark.ast.Code
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.Heading
import com.vladsch.flexmark.ast.ListItem
import com.vladsch.flexmark.ast.OrderedList
import com.vladsch.flexmark.ast.Paragraph
import com.vladsch.flexmark.ast.Text
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node

/**
 * Markdown → DocNode tree. Ported from doc-store `com.docstore.ingestion.mdHandler`
 * (framework-agnostic; package + `AppConfig` → [IngestionConfig] are the only
 * changes). Headings create hierarchy; lists are flattened with two-space
 * indentation and kept as single blocks; code blocks are kept intact.
 */
fun extractPlainTextFromMarkdown(
    md: String,
    config: IngestionConfig,
): String = parseMarkdownToTree(md, config).text

fun parseMarkdownToTree(
    md: String,
    config: IngestionConfig,
    fileName: String? = null,
): DocNode {
    val parser = Parser.builder().build()
    val document = parser.parse(md)

    var root =
        DocNode(
            id = 0,
            level = 0,
            parentIndex = 0,
            title = fileName?.substringBeforeLast('.'),
            text = "",
            ownText = "",
            children = mutableListOf(),
            type = DocNodeType.DOC,
        )
    val stack = ArrayDeque<DocNode>()
    stack.add(root)

    var n: Node? = document.firstChild
    while (n != null) {
        handleMdNode(n, stack, config)
        n = n.next
    }

    fun computeTexts(node: DocNode): DocNode {
        val newChildren = node.children.map { computeTexts(it) }.toMutableList()
        val childrenText = newChildren.map { it.text }.filter { it.isNotBlank() }
        val own = node.ownText.trim()
        val whole =
            (listOf(own).filter { it.isNotEmpty() } + childrenText)
                .joinToString("\n\n")
                .trim()
        return node.copy(children = newChildren, text = whole, ownText = own).also { copy ->
            copy.children.forEachIndexed { i, c -> copy.children[i] = c.copy(parentIndex = i) }
        }
    }

    root = computeTexts(root)
    return root
}

private fun handleMdNode(
    n: Node,
    stack: ArrayDeque<DocNode>,
    config: IngestionConfig,
) {
    when (n) {
        is Heading -> {
            val lvl = n.level.coerceIn(1, 7)
            while (stack.isNotEmpty() && stack.last().level >= lvl) stack.removeLast()
            val parent = stack.last()
            val title = normalizeInline(textOf(n))
            val type =
                when (lvl) {
                    1 -> DocNodeType.H1
                    2 -> DocNodeType.H2
                    3 -> DocNodeType.H3
                    4 -> DocNodeType.H4
                    5 -> DocNodeType.H5
                    6 -> DocNodeType.H6
                    else -> DocNodeType.H7
                }
            val child =
                DocNode(
                    id = 0,
                    level = lvl,
                    parentIndex = parent.children.size,
                    title = title,
                    text = "",
                    ownText = "",
                    children = mutableListOf(),
                    type = type,
                )
            parent.children += child
            stack.add(child)
        }
        is Paragraph -> {
            val t = normalizeBlock(textOf(n))
            if (t.isNotBlank()) addLeafMd(stack.last(), t, DocNodeType.OTHER, config)
        }
        is BulletList -> {
            val items = flattenList(n)
            val txt = items.joinToString("\n")
            addLeafMd(stack.last(), txt, DocNodeType.LIST, config)
        }
        is OrderedList -> {
            val items = flattenList(n)
            val txt = items.joinToString("\n")
            addLeafMd(stack.last(), txt, DocNodeType.LIST, config)
        }
        is FencedCodeBlock -> {
            val code =
                n.contentChars
                    .normalizeEOL()
                    .toString()
                    .trimEnd('\n')
            if (code.isNotBlank()) addLeafMd(stack.last(), code, DocNodeType.CODE, config)
        }
        else -> {
            var c = n.firstChild
            while (c != null) {
                handleMdNode(c, stack, config)
                c = c.next
            }
        }
    }
}

private fun textOf(n: Node): String {
    val sb = StringBuilder()

    fun walk(x: Node?) {
        var cur = x
        while (cur != null) {
            when (cur) {
                is Text -> sb.append(cur.chars.toString())
                is Code -> sb.append(cur.text.toString())
                else -> walk(cur.firstChild)
            }
            cur = cur.next
        }
    }
    walk(n.firstChild)
    if (sb.isEmpty()) sb.append(n.chars.toString())
    return sb.toString()
}

private fun flattenList(
    list: Node,
    indent: Int = 0,
): List<String> {
    val out = mutableListOf<String>()
    var idx = 1
    var item: Node? = list.firstChild
    while (item != null) {
        val current = item
        if (current is ListItem) {
            val pText =
                buildString {
                    var c: Node? = current.firstChild
                    while (c != null) {
                        when (c) {
                            is Paragraph -> append(normalizeInline(textOf(c)))
                            is Text -> append(normalizeInline(c.chars.toString()))
                            is FencedCodeBlock -> append(c.contentChars.toString())
                        }
                        c = c.next
                    }
                }.trim()
            val prefix = if (list is OrderedList) "$idx." else "-"
            val line = (" ".repeat(indent * 2) + "$prefix " + pText).trimEnd()
            if (pText.isNotEmpty()) out += line
            var c2: Node? = current.firstChild
            while (c2 != null) {
                if (c2 is BulletList || c2 is OrderedList) {
                    out += flattenList(c2, indent + 1)
                }
                c2 = c2.next
            }
            idx++
        }
        item = item.next
    }
    return out
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

private fun addLeafMd(
    parent: DocNode,
    content: String,
    type: DocNodeType,
    config: IngestionConfig,
) {
    val normalized = normalizeBlock(content)
    if (normalized.isEmpty() && type != DocNodeType.OTHER) return
    val level = (parent.level + 1).coerceAtMost(7)
    if (type == DocNodeType.OTHER) {
        val parts = splitIntoParagraphs(normalized, config.splitterParagraphMinLen, config.splitterParagraphMaxLen)
        for (p in parts) {
            val leaf =
                DocNode(
                    id = 0,
                    level = level,
                    parentIndex = parent.children.size,
                    title = null,
                    text = p.text,
                    ownText = p.text,
                    children = mutableListOf(),
                    type = type,
                )
            parent.children += leaf
        }
    } else {
        val leaf =
            DocNode(
                id = 0,
                level = level,
                parentIndex = parent.children.size,
                title = null,
                text = normalized,
                ownText = normalized,
                children = mutableListOf(),
                type = type,
            )
        parent.children += leaf
    }
}
