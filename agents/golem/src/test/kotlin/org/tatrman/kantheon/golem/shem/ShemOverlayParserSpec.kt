package org.tatrman.kantheon.golem.shem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Behavioural contract for [ShemOverlayParser] — the `kantheon.shem/v1` overlay
 * (identity + per-agent residue). This replaces the retired rich-Shem parse: the
 * model-derived fields + template constants are NOT here (they come from
 * [ShemAssembler]). Strict-fail discipline: a malformed overlay or a missing
 * required key is fatal at boot.
 */
class ShemOverlayParserSpec :
    StringSpec({

        "parses a valid overlay into source + residue blocks" {
            val o = ShemOverlayParser.parse(VALID_OVERLAY_YAML)
            o.apiVersion shouldBe "kantheon.shem/v1"
            o.kind shouldBe "golem-shem"
            o.source.id shouldBe "ucetnictvi"
            o.source.label shouldBe "Účetnictví"
            o.source.areas shouldContainExactly listOf("accounting")
            o.overlay.visibilityRoles shouldContainExactly listOf("kantheon-area-accounting")
            o.overlay.descriptionForRouter shouldContain "Účetnictví"
            o.overlay.exampleQuestions.single() shouldContain "4902"
            o.overlay.counterExamples.single() shouldContain "marže"
            o.overlay.localeDefaults
                .single()
                .currency shouldBe "CZK"
        }

        "a minimal overlay parses with the optional residue absent" {
            val o = ShemOverlayParser.parse(MINIMAL_OVERLAY_YAML)
            o.source.id shouldBe "ucetnictvi"
            o.overlay.descriptionForRouter shouldBe ""
            o.overlay.exampleQuestions.shouldBeEmpty()
            o.overlay.localeDefaults.shouldBeEmpty()
        }

        "malformed YAML throws ShemValidationException" {
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse("not: [valid: yaml") }
        }

        "a wrong apiVersion is rejected" {
            val bad = VALID_OVERLAY_YAML.replace("apiVersion: kantheon.shem/v1", "apiVersion: kantheon.shem/v2")
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse(bad) }
                .message shouldContain "apiVersion"
        }

        "a wrong kind is rejected" {
            val bad = VALID_OVERLAY_YAML.replace("kind: golem-shem", "kind: not-a-shem")
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse(bad) }
                .message shouldContain "kind"
        }

        "a blank source.id is rejected" {
            val bad = VALID_OVERLAY_YAML.replace("id: ucetnictvi", "id: \"\"")
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse(bad) }
                .message shouldContain "source.id"
        }

        "a blank source.label is rejected" {
            val bad = VALID_OVERLAY_YAML.replace("label: \"Účetnictví\"", "label: \"\"")
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse(bad) }
                .message shouldContain "source.label"
        }

        "an empty source.areas is rejected" {
            val bad = VALID_OVERLAY_YAML.replace("areas: [accounting]", "areas: []")
            shouldThrow<ShemValidationException> { ShemOverlayParser.parse(bad) }
                .message shouldContain "source.areas"
        }
    })
