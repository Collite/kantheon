package org.tatrman.kantheon.golem.shem

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reconciles the **real** golem-ucetnictvi Shem bundle (`shems/golem-ucetnictvi/`)
 * against the live `ShemOverlayParser` + `PromptStore` layout — the first Kantheon
 * Golem's mounted bundle (Stage 4.4 T1). Catches drift between the authored bundle
 * and the assembly contract before the pod is deployed (the ahead-of-cluster pattern).
 */
class GolemUcetnictviBundleSpec :
    StringSpec({

        // golem module working dir is agents/golem; the bundle is checked in beside it.
        val bundle = Path.of("shems/golem-ucetnictvi")

        "the bundle overlay parses and carries the expected identity + entitlement" {
            val overlay = ShemOverlayParser.parse(Files.readString(bundle.resolve("shem.yaml")))

            overlay.apiVersion shouldBe "kantheon.shem/v1"
            overlay.kind shouldBe "golem-shem"
            overlay.source.id shouldBe "ucetnictvi"
            overlay.source.label shouldBe "Účetnictví"
            overlay.source.areas shouldContainExactly listOf("accounting")
            overlay.overlay.visibilityRoles shouldContain "kantheon-area-accounting"
        }

        "the bundle ships the cs + en prompt sets the PromptStore expects" {
            for (locale in listOf("cs", "en")) {
                for (name in listOf("intent", "free-sql", "chip-topup")) {
                    val p = bundle.resolve("prompts/$locale/$name.yaml")
                    Files.exists(p) shouldBe true
                }
            }
        }
    })
