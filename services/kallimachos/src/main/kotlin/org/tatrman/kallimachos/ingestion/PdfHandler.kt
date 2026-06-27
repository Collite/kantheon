package org.tatrman.kallimachos.ingestion

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * PDF → DocNode tree. Ported from doc-store `com.docstore.ingestion.pdfHandler`
 * (framework-agnostic; package + `AppConfig` → [IngestionConfig] are the only
 * changes). Two-level tree: pages (PAGE) → paragraphs (PARA). A page guardrail
 * (`maxPdfPages`) returns null rather than over-reading huge PDFs.
 */
fun extractTextFromPdf(
    bytes: ByteArray,
    config: IngestionConfig,
): String? =
    try {
        Loader.loadPDF(bytes).use { doc ->
            if (doc.numberOfPages > config.maxPdfPages) {
                null
            } else {
                val stripper =
                    PDFTextStripper().apply {
                        startPage = 1
                        endPage = doc.numberOfPages
                    }
                stripper.getText(doc)
            }
        }
    } catch (e: Exception) {
        null
    }

fun parsePdfToTree(
    bytes: ByteArray,
    config: IngestionConfig,
): DocNode? =
    try {
        Loader.loadPDF(bytes).use { doc ->
            if (doc.numberOfPages > config.maxPdfPages) {
                null
            } else {
                val rootChildren = mutableListOf<DocNode>()
                val root =
                    DocNode(
                        id = 0,
                        level = 0,
                        parentIndex = 0,
                        title = null,
                        text = "",
                        ownText = "",
                        children = rootChildren,
                        type = DocNodeType.OTHER,
                    )

                for (pageIdx in 0 until doc.numberOfPages) {
                    val stripper =
                        PDFTextStripper().apply {
                            startPage = pageIdx + 1
                            endPage = pageIdx + 1
                        }
                    val pageText = stripper.getText(doc).replace("\r\n", "\n").trim()
                    val paraParts =
                        splitIntoParagraphs(
                            normalizedText = pageText,
                            minWords = config.splitterParagraphMinLen,
                            maxWords = config.splitterParagraphMaxLen,
                        )
                    val paraNodes =
                        paraParts
                            .map { p ->
                                DocNode(
                                    id = 0,
                                    level = 2,
                                    parentIndex = p.index,
                                    title = null,
                                    text = p.text,
                                    ownText = p.text,
                                    children = mutableListOf(),
                                    type = DocNodeType.PARA,
                                )
                            }.toMutableList()
                    val pageCombined = paraNodes.joinToString("\n\n") { it.text }.trim()
                    val pageNode =
                        DocNode(
                            id = 0,
                            level = 1,
                            parentIndex = pageIdx,
                            title = null,
                            text = pageCombined,
                            ownText = "",
                            children = paraNodes,
                            type = DocNodeType.PAGE,
                        )
                    rootChildren += pageNode
                }

                val combined = rootChildren.joinToString("\n\n") { it.text }.trim()
                root.copy(text = combined, children = rootChildren)
            }
        }
    } catch (e: Exception) {
        null
    }
