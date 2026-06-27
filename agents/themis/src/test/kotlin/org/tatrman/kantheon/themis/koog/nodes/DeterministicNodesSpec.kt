package org.tatrman.kantheon.themis.koog.nodes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.themis.client.NlpEntity
import org.tatrman.kantheon.themis.client.NlpToken
import org.tatrman.kantheon.themis.v1.Themis

/**
 * Stage 2.3 T3 spec — direct coverage of the pure step functions both
 * `ThemisGraphDispatch` and `themisGraph` delegate to. The full
 * `ResolverGraphNodeTest` integration suite still asserts end-to-end
 * behaviour through the legacy graph; this spec gives focused, fast-running
 * coverage of the deterministic logic with descriptive failure messages.
 */
class DeterministicNodesSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // universalTypeFor — label normalisation
        // -------------------------------------------------------------------------

        "universalTypeFor — PER / PERSON map to PERSON; LOC / LOCATION map to LOCATION" {
            universalTypeFor("PER") shouldBe Themis.UniversalEntityType.PERSON
            universalTypeFor("PERSON") shouldBe Themis.UniversalEntityType.PERSON
            universalTypeFor("LOC") shouldBe Themis.UniversalEntityType.LOCATION
            universalTypeFor("LOCATION") shouldBe Themis.UniversalEntityType.LOCATION
        }

        "universalTypeFor — ORG, DATE/TIME, MONEY/AMOUNT routing" {
            universalTypeFor("ORG") shouldBe Themis.UniversalEntityType.ORGANIZATION
            universalTypeFor("ORGANIZATION") shouldBe Themis.UniversalEntityType.ORGANIZATION
            universalTypeFor("DATE") shouldBe Themis.UniversalEntityType.DATE
            universalTypeFor("TIME") shouldBe Themis.UniversalEntityType.DATE
            universalTypeFor("MONEY") shouldBe Themis.UniversalEntityType.MONEY
            universalTypeFor("AMOUNT") shouldBe Themis.UniversalEntityType.MONEY
        }

        "universalTypeFor — unknown labels fall through to MISC; case-insensitive" {
            universalTypeFor("TECH") shouldBe Themis.UniversalEntityType.MISC
            universalTypeFor("date") shouldBe Themis.UniversalEntityType.DATE
            universalTypeFor("money") shouldBe Themis.UniversalEntityType.MONEY
        }

        // -------------------------------------------------------------------------
        // normaliseUniversal — full NlpEntity → UniversalEntityNormalized mapping
        // -------------------------------------------------------------------------

        "normaliseUniversal — preserves rawText, normalizedValue, sourceEngine, char offsets" {
            val entity =
                NlpEntity(
                    text = "tomorrow",
                    label = "DATE",
                    charStart = 4,
                    charEnd = 12,
                    normalizedValue = "2026-05-31",
                    sourceEngine = "udpipe",
                )
            val out = normaliseUniversal(entity)
            out.rawText shouldBe "tomorrow"
            out.entityType shouldBe Themis.UniversalEntityType.DATE
            out.normalizedValue shouldBe "2026-05-31"
            out.sourceEngine shouldBe "udpipe"
            out.charStart shouldBe 4
            out.charEnd shouldBe 12
        }

        // -------------------------------------------------------------------------
        // proposeDomainSpansFromTokens — UPOS-driven noun-head extraction
        // -------------------------------------------------------------------------

        "proposeDomainSpansFromTokens — keeps NOUN + PROPN, drops everything else" {
            val tokens =
                listOf(
                    token(text = "Show", upos = "VERB"),
                    token(text = "invoices", upos = "NOUN", charStart = 5, charEnd = 13),
                    token(text = "for", upos = "ADP"),
                    token(text = "ACME", upos = "PROPN", charStart = 18, charEnd = 22),
                )
            val spans = proposeDomainSpansFromTokens(tokens)
            spans shouldHaveSize 2
            spans[0].coveredText shouldBe "invoices"
            spans[0].pos shouldBe "NOUN"
            spans[1].coveredText shouldBe "ACME"
            spans[1].pos shouldBe "PROPN"
        }

        "proposeDomainSpansFromTokens — empty token list returns empty span list" {
            proposeDomainSpansFromTokens(emptyList()).shouldHaveSize(0)
        }

        "proposeDomainSpansFromTokens — only VERB / ADP / DET tokens returns empty" {
            proposeDomainSpansFromTokens(
                listOf(
                    token("Show", "VERB"),
                    token("for", "ADP"),
                    token("the", "DET"),
                ),
            ).shouldHaveSize(0)
        }
    })

private fun token(
    text: String,
    upos: String,
    charStart: Int = 0,
    charEnd: Int = text.length,
    lemma: String = text.lowercase(),
    xpos: String = "",
    depHead: Int = 0,
    depRelation: String = "",
): NlpToken =
    NlpToken(
        text = text,
        charStart = charStart,
        charEnd = charEnd,
        lemma = lemma,
        upos = upos,
        xpos = xpos,
        feats = emptyMap(),
        depHead = depHead,
        depRelation = depRelation,
    )
