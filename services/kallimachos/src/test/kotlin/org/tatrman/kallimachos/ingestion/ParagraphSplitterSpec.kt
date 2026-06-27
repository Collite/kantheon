package org.tatrman.kallimachos.ingestion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeBetween
import io.kotest.matchers.shouldBe

/**
 * Parity port of doc-store `ParagraphSplitterTest` — the splitter is the parity
 * oracle for the "Spring→Ktor rewrite drops a doc-store behaviour" risk
 * (architecture §14). Behaviour must match the doc-store baseline byte-for-byte.
 */
class ParagraphSplitterSpec :
    StringSpec({
        "splits on blank lines, trims, preserves order and indices" {
            val text =
                """
                First paragraph line 1.

                Second paragraph line 1.
                Second paragraph line 2.


                Third paragraph.
                """.trimIndent()

            val parts = splitIntoParagraphs(text, minWords = 1, maxWords = 200)
            parts.shouldHaveSize(3)
            parts[0].index shouldBe 0
            parts[1].index shouldBe 1
            parts[2].index shouldBe 2
            parts[0].text.contains("First paragraph") shouldBe true
            parts[1].text.contains("Second paragraph") shouldBe true
            parts[2].text.contains("Third paragraph") shouldBe true
        }

        "merges short parts below minWords" {
            var text =
                """
                a b

                c d

                e f g h i
                """.trimIndent()
            var parts = splitIntoParagraphs(text, minWords = 5, maxWords = 200)
            parts.shouldHaveSize(1)
            parts[0].wordCount.shouldBeBetween(5, 10)
            parts[0].index shouldBe 0

            text =
                """
                a b x

                c d

                e f g h i
                """.trimIndent()
            parts = splitIntoParagraphs(text, minWords = 5, maxWords = 200)
            parts.shouldHaveSize(2)
            parts[0].wordCount.shouldBeBetween(5, 10)
            parts[1].wordCount shouldBe 5
            parts[0].index shouldBe 0
            parts[1].index shouldBe 1
        }

        "splits long parts into maxWords chunks" {
            val words = (1..25).joinToString(" ") { "w$it" }
            val parts = splitIntoParagraphs(words, minWords = 1, maxWords = 10)
            parts.shouldHaveSize(3)
            parts[0].wordCount shouldBe 10
            parts[1].wordCount shouldBe 10
            parts[2].wordCount shouldBe 5
        }

        "blank input yields no parts" {
            splitIntoParagraphs("   \n\n  ", minWords = 1, maxWords = 200).shouldHaveSize(0)
        }
    })
