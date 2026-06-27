package org.tatrman.kallimachos.ingestion

/**
 * Mime-dispatching front door for the ported parsers (architecture §1: "parse
 * txt/md/html/pdf → DocNode → parts"). Falls back to the plain-text handler for
 * unknown types. PDF needs the raw bytes; the text formats decode UTF-8.
 */
class DocumentParser(
    private val config: IngestionConfig = IngestionConfig.DEFAULT,
) {
    fun parse(
        content: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ): DocNode {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        return when {
            mime == "application/pdf" ->
                parsePdfToTree(content, config) ?: plainTextToDocNode("", config)
            mime == "text/html" || mime == "application/xhtml+xml" ->
                parseHtmlToTree(content.decodeToString(), config, fileName)
            mime == "text/markdown" || mime == "text/x-markdown" || fileName?.endsWith(".md") == true ->
                parseMarkdownToTree(content.decodeToString(), config, fileName)
            else ->
                plainTextToDocNode(content.decodeToString(), config)
        }
    }
}

/**
 * Flatten a parsed [DocNode] tree into ordered, citeable paragraph parts — the
 * leaf content nodes in document order. Headings are structure, not parts (their
 * text already flows through their leaf children), so only childless non-blank
 * nodes become parts. This is the `DocNode → parts` step.
 */
fun DocNode.toParts(): List<String> {
    val out = mutableListOf<String>()

    fun walk(n: DocNode) {
        if (n.children.isEmpty()) {
            val t = n.text.trim()
            if (t.isNotBlank()) out += t
        } else {
            n.children.forEach { walk(it) }
        }
    }
    walk(this)
    return out
}
