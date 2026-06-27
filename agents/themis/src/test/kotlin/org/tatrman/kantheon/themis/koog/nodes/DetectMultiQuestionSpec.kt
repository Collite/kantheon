package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.themis.client.NlpToken

/**
 * Phase 3 Stage 3.2 — deterministic multi-question detection over UD
 * dependency parses. Fixtures carry hand-authored `upos`/`dep_relation`/
 * `dep_head` (1-based) so the detector's clause-splitting is exercised directly.
 */
class DetectMultiQuestionSpec :
    StringSpec({

        "single question — does not fire (one clause head)" {
            // Které faktury Shell neuhradil?
            val parse =
                sentence(
                    tok("Které", "DET", "det", head = 2),
                    tok("faktury", "NOUN", "obj", head = 4),
                    tok("Shell", "PROPN", "nsubj", head = 4),
                    tok("neuhradil", "VERB", "root", head = 0),
                )
            detectMultiQuestion(parse) shouldBe DetectMultiQuestionOutput.SingleQuestion
        }

        "two disjoint clauses with distinct topics — fires" {
            // Které faktury Shell neuhradil a jaká byla Q3 marže?
            val parse =
                sentence(
                    tok("Které", "DET", "det", head = 2),
                    tok("faktury", "NOUN", "obj", head = 4),
                    tok("Shell", "PROPN", "nsubj", head = 4),
                    tok("neuhradil", "VERB", "root", head = 0),
                    tok("a", "CCONJ", "cc", head = 7),
                    tok("jaká", "DET", "det", head = 9),
                    tok("byla", "VERB", "conj", head = 4),
                    tok("Q3", "PROPN", "compound", head = 9),
                    tok("marže", "NOUN", "nsubj", head = 7),
                )
            detectMultiQuestion(parse) shouldBe
                DetectMultiQuestionOutput.MultiQuestion(
                    listOf("Které faktury Shell neuhradil?", "Jaká byla Q3 marže?"),
                )
        }

        "two clauses tied by an anaphoric pronoun — does NOT fire" {
            // Které faktury Shell neuhradil a jaká je jejich celková částka?
            val parse =
                sentence(
                    tok("Které", "DET", "det", head = 2),
                    tok("faktury", "NOUN", "obj", head = 4),
                    tok("Shell", "PROPN", "nsubj", head = 4),
                    tok("neuhradil", "VERB", "root", head = 0),
                    tok("a", "CCONJ", "cc", head = 7),
                    tok("jaká", "DET", "det", head = 10),
                    tok("je", "VERB", "conj", head = 4),
                    tok("jejich", "PRON", "nmod:poss", head = 10, lemma = "jejich"),
                    tok("celková", "ADJ", "amod", head = 10),
                    tok("částka", "NOUN", "nsubj", head = 7),
                )
            detectMultiQuestion(parse) shouldBe DetectMultiQuestionOutput.SingleQuestion
        }

        "second clause has no content noun (shared subject) — does NOT fire" {
            // Které faktury Shell neuhradil a má zaplatit?
            val parse =
                sentence(
                    tok("Které", "DET", "det", head = 2),
                    tok("faktury", "NOUN", "obj", head = 4),
                    tok("Shell", "PROPN", "nsubj", head = 4),
                    tok("neuhradil", "VERB", "root", head = 0),
                    tok("a", "CCONJ", "cc", head = 6),
                    tok("má", "VERB", "conj", head = 4),
                    tok("zaplatit", "VERB", "xcomp", head = 6),
                )
            detectMultiQuestion(parse) shouldBe DetectMultiQuestionOutput.SingleQuestion
        }

        "English two copular clauses with 'and' — fires" {
            // What are unpaid invoices and what is Q3 margin?
            val parse =
                sentence(
                    tok("What", "PRON", "nsubj", head = 4, lemma = "what"),
                    tok("are", "AUX", "cop", head = 4),
                    tok("unpaid", "ADJ", "amod", head = 4),
                    tok("invoices", "NOUN", "root", head = 0),
                    tok("and", "CCONJ", "cc", head = 9),
                    tok("what", "PRON", "nsubj", head = 9, lemma = "what"),
                    tok("is", "AUX", "cop", head = 9),
                    tok("Q3", "PROPN", "compound", head = 9),
                    tok("margin", "NOUN", "conj", head = 4),
                )
            detectMultiQuestion(parse).shouldBeInstanceOf<DetectMultiQuestionOutput.MultiQuestion>()
        }
    })

/** Builds an [NlpToken] with UD annotations; charStart is filled by [sentence]. */
private class Tok(
    val text: String,
    val upos: String,
    val dep: String,
    val head: Int,
    val lemma: String,
)

private fun tok(
    text: String,
    upos: String,
    dep: String,
    head: Int,
    lemma: String = text.lowercase(),
): Tok = Tok(text, upos, dep, head, lemma)

/** Assembles tokens in source order, assigning increasing charStart offsets. */
private fun sentence(vararg toks: Tok): List<NlpToken> {
    var offset = 0
    return toks.map { t ->
        val start = offset
        offset += t.text.length + 1
        NlpToken(
            text = t.text,
            charStart = start,
            charEnd = start + t.text.length,
            lemma = t.lemma,
            upos = t.upos,
            xpos = "",
            feats = emptyMap(),
            depHead = t.head,
            depRelation = t.dep,
        )
    }
}
