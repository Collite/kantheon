package org.tatrman.kallimachos.ingestion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * `plainTextToDocNode` (the text handler) — splits a plain document into PARA
 * leaves under a single OTHER root, normalising CRLF. Parity with doc-store
 * `textHandler.plainTextToDocNode`.
 */
class TextHandlerSpec :
    StringSpec({
        val cfg = IngestionConfig(splitterParagraphMinLen = 1, splitterParagraphMaxLen = 200)

        "plain text fans out into PARA children under an OTHER root" {
            val text = "Alpha paragraph here.\n\nBeta paragraph here.\n\nGamma paragraph here."
            val node = plainTextToDocNode(text, cfg)
            node.type shouldBe DocNodeType.OTHER
            node.level shouldBe 0
            node.children shouldHaveSize 3
            node.children.all { it.type == DocNodeType.PARA } shouldBe true
            node.children.mapIndexed { i, c -> c.parentIndex to i }.all { it.first == it.second } shouldBe true
            node.text.contains("Alpha") shouldBe true
            node.text.contains("Gamma") shouldBe true
        }

        "CRLF newlines normalise to LF before splitting" {
            val text = "One.\r\n\r\nTwo."
            val node = plainTextToDocNode(text, cfg)
            node.children shouldHaveSize 2
        }
    })
