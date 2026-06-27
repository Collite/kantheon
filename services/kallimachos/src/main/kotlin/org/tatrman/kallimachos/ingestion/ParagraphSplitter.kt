package org.tatrman.kallimachos.ingestion

/**
 * Deterministic paragraph splitter + the plain-text handler.
 *
 * Ported from doc-store `com.docstore.ingestion.textHandler` (framework-agnostic;
 * package + `AppConfig` → [IngestionConfig] are the only changes). Splits on two
 * or more newlines, trims, discards empty; then enforces min/max word constraints
 * by merging short parts forward and chunking long parts. The doc-store
 * `ParagraphSplitterTest` corpus is the parity oracle.
 */
data class ParagraphPart(
    val index: Int,
    val text: String,
    val contentLength: Int,
    val wordCount: Int,
)

fun splitIntoParagraphs(
    normalizedText: String,
    minWords: Int,
    maxWords: Int,
): List<ParagraphPart> {
    if (normalizedText.isBlank()) return emptyList()
    val raw =
        normalizedText
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // First, naive parts with word counts
    val initial =
        raw.map {
            it to
                it.split(Regex("\\s+")).filter { w -> w.isNotBlank() }
        }

    // Merge short parts with next
    val merged = mutableListOf<List<String>>()
    var i = 0
    while (i < initial.size) {
        val words = initial[i].second.toMutableList()
        var j = i + 1
        while (words.size < minWords && j < initial.size) {
            words.addAll(initial[j].second)
            j++
        }
        merged.add(words)
        i = j
    }

    // Split long parts into chunks of up to maxWords
    val finalChunks = mutableListOf<List<String>>()
    for (lst in merged) {
        var start = 0
        while (start < lst.size) {
            val end = kotlin.math.min(start + maxWords, lst.size)
            finalChunks.add(lst.subList(start, end))
            start = end
        }
    }

    return finalChunks.mapIndexed { idx, words ->
        val text = words.joinToString(" ")
        ParagraphPart(index = idx, text = text, contentLength = text.length, wordCount = words.size)
    }
}

fun plainTextToDocNode(
    text: String,
    config: IngestionConfig,
): DocNode {
    val normalized = text.replace("\r\n", "\n").trim()
    val parts =
        splitIntoParagraphs(
            normalizedText = normalized,
            minWords = config.splitterParagraphMinLen,
            maxWords = config.splitterParagraphMaxLen,
        )
    val children =
        parts
            .map { p ->
                DocNode(
                    id = 0,
                    level = 1,
                    parentIndex = p.index,
                    title = null,
                    text = p.text,
                    ownText = p.text,
                    children = mutableListOf(),
                    type = DocNodeType.PARA,
                )
            }.toMutableList()
    val combined = children.joinToString("\n\n") { it.text }.trim()
    return DocNode(
        id = 0,
        level = 0,
        parentIndex = 0,
        title = null,
        text = combined,
        ownText = "",
        children = children,
        type = DocNodeType.OTHER,
    )
}
