package org.tatrman.kantheon.hebe.memory.workspace

import java.util.regex.Pattern

data class MarkdownMetadata(
    val extension: String,
    val title: String?,
    val headings: List<String>,
    val frontmatter: Map<String, String>?,
)

object MarkdownInferrer {
    private val FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n", Pattern.MULTILINE)
    private val HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE)

    fun metadata(
        path: String,
        content: String,
    ): MarkdownMetadata {
        val extension = path.substringAfterLast('.', "")
        val frontmatter = parseFrontmatter(content)
        val title = extractTitle(content, frontmatter)
        val headings = extractHeadings(content)
        return MarkdownMetadata(extension, title, headings, frontmatter)
    }

    private fun parseFrontmatter(content: String): Map<String, String>? {
        val matcher = FRONTMATTER_PATTERN.matcher(content)
        if (!matcher.find()) return null
        val fm = mutableMapOf<String, String>()
        for (line in matcher.group(1).split("\n")) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                fm[parts[0].trim()] = parts[1].trim().trimMargin("\"")
            }
        }
        return fm
    }

    @Suppress("ReturnCount")
    private fun extractTitle(
        content: String,
        frontmatter: Map<String, String>?,
    ): String? {
        val title = frontmatter?.get("title")
        if (title != null) return title
        for (line in content.lines()) {
            if (line.startsWith("# ")) return line.substring(2).trim()
        }
        return null
    }

    private fun extractHeadings(content: String): List<String> {
        val headings = mutableListOf<String>()
        FRONTMATTER_PATTERN.matcher(content).let { matcher ->
            val body = if (matcher.find()) content.substring(matcher.end()) else content
            for (line in body.split("\n")) {
                val m = HEADING_PATTERN.matcher(line)
                if (m.find()) headings.add(m.group(1).trim())
            }
        }
        return headings
    }
}
