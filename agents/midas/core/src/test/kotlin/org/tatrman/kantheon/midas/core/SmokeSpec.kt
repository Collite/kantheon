package org.tatrman.kantheon.midas.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Stage 1.1 skeleton smoke test — proves the module's unit-test wiring (Kotest
 * on the unit `test` source set). Real route/repository/derivation specs land
 * in Stage 1.3.
 */
class SmokeSpec :
    StringSpec({
        "module wiring compiles and the test tier runs" {
            (1 + 1) shouldBe 2
        }
    })
