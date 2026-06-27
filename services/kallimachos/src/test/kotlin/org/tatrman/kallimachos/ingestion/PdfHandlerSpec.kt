package org.tatrman.kallimachos.ingestion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/**
 * PDF handler — pages → PARA leaves; the `maxPdfPages` guardrail. The fixtures
 * are generated in-process with PDFBox (no binary blobs in the tree), so the
 * spec is the parity oracle for `pdfHandler` without an external corpus.
 */
class PdfHandlerSpec :
    StringSpec({
        fun pdfBytes(vararg pages: String): ByteArray {
            PDDocument().use { doc ->
                for (text in pages) {
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        cs.newLineAtOffset(50f, 700f)
                        cs.showText(text)
                        cs.endText()
                    }
                }
                val baos = ByteArrayOutputStream()
                doc.save(baos)
                return baos.toByteArray()
            }
        }

        val cfg = IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200, maxPdfPages = 10)

        "a single-page PDF parses into a PAGE node with PARA leaves" {
            val bytes = pdfBytes("Hello warehouse this is page one.")
            val root = parsePdfToTree(bytes, cfg)
            root.shouldNotBeNull()
            val pageNode = root.children.first()
            pageNode.type shouldBe DocNodeType.PAGE
            pageNode.children.all { it.type == DocNodeType.PARA } shouldBe true
            root.text shouldContain "Hello warehouse"
        }

        "extractTextFromPdf returns the page text" {
            val text = extractTextFromPdf(pdfBytes("Just some content."), cfg)
            text.shouldNotBeNull()
            text shouldContain "Just some content."
        }

        "the maxPdfPages guardrail returns null when exceeded" {
            val twoPages = pdfBytes("page one", "page two")
            val strict = IngestionConfig(maxPdfPages = 1)
            parsePdfToTree(twoPages, strict) shouldBe null
            extractTextFromPdf(twoPages, strict) shouldBe null
        }
    })
