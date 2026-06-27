package org.tatrman.kallimachos.ingestion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * HTML handler — title extraction, heading hierarchy, list flattening. Parity
 * with doc-store `htmlHandler`.
 */
class HtmlHandlerSpec :
    StringSpec({
        val cfg = IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200)

        "document title is taken from <title>" {
            val html = "<html><head><title>My Doc</title></head><body><p>hi</p></body></html>"
            parseHtmlToTree(html, cfg).title shouldBe "My Doc"
        }

        "headings build a hierarchy and carry their text" {
            val html =
                """
                <html><head><title>T</title></head><body>
                  <h1>Title</h1>
                  <p>Intro paragraph.</p>
                  <h2>Section A</h2>
                  <p>Body of A.</p>
                </body></html>
                """.trimIndent()
            val root = parseHtmlToTree(html, cfg)

            fun collect(n: DocNode): List<DocNode> = listOf(n) + n.children.flatMap { collect(it) }
            val all = collect(root)
            all.any { it.type == DocNodeType.H1 && it.title == "Title" } shouldBe true
            all.any { it.type == DocNodeType.H2 && it.title == "Section A" } shouldBe true
            root.text shouldContain "Intro paragraph."
        }

        "ordered + bullet lists flatten with numbering / dashes" {
            val html =
                """
                <html><head><title>L</title></head><body>
                  <ol><li>first</li><li>second</li></ol>
                  <ul><li>alpha</li><li>beta</li></ul>
                </body></html>
                """.trimIndent()
            val root = parseHtmlToTree(html, cfg)
            val text = root.text
            text shouldContain "1. first"
            text shouldContain "2. second"
            text shouldContain "- alpha"
        }
    })
