package org.tatrman.kantheon.iris.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class StaticChipSourceSpec :
    StringSpec({

        "loads the shipped static-chips.yaml into PromptChips" {
            val chips = StaticChipSource().chips()
            chips.isNotEmpty() shouldBe true
            chips.all { it.source == "static" } shouldBe true
            chips.map { it.display } shouldContain "Tržby za měsíc"
            chips.first { it.display == "Tržby za měsíc" }.prompt shouldBe "Kolik jsme prodali minulý měsíc?"
        }

        "a missing resource yields no chips (best-effort, never throws)" {
            StaticChipSource("/does-not-exist.yaml").chips() shouldBe emptyList()
        }
    })
