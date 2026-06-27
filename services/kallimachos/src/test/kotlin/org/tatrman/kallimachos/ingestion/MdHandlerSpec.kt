package org.tatrman.kallimachos.ingestion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Markdown handler — headings create hierarchy; lists are flattened with two-space
 * indentation; fenced code is kept intact. Parity with doc-store `mdHandler`.
 */
class MdHandlerSpec :
    StringSpec({
        val cfg = IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200)

        "headings build a DocNode hierarchy under the DOC root" {
            val md =
                """
                # Title

                Intro paragraph.

                ## Section A

                Body of A.
                """.trimIndent()
            val root = parseMarkdownToTree(md, cfg)
            root.type shouldBe DocNodeType.DOC
            val h1 = root.children.first { it.type == DocNodeType.H1 }
            h1.title shouldBe "Title"
            // The H2 nests under the H1 (heading hierarchy via the level stack).
            val h2 = h1.children.firstOrNull { it.type == DocNodeType.H2 }
            (h2 != null) shouldBe true
            h2!!.title shouldBe "Section A"
        }

        "ordered + bullet lists flatten with numbering / dashes preserved" {
            val md =
                """
                # L

                1. first
                2. second

                - alpha
                - beta
                """.trimIndent()
            val root = parseMarkdownToTree(md, cfg)
            val text = root.text
            text shouldContain "1. first"
            text shouldContain "2. second"
            text shouldContain "- alpha"
        }

        "fenced code blocks are kept intact as CODE leaves" {
            val md =
                """
                # C

                ```
                val x = 1
                val y = 2
                ```
                """.trimIndent()
            val root = parseMarkdownToTree(md, cfg)

            fun collect(n: DocNode): List<DocNode> = listOf(n) + n.children.flatMap { collect(it) }
            val code = collect(root).first { it.type == DocNodeType.CODE }
            code.text shouldContain "val x = 1"
            code.text shouldContain "val y = 2"
        }
    })
