package org.tatrman.kantheon.report

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Stage 1.1 skeleton smoke test. Template-resolver + render-engine specs land in
 * Phase 3 Stage 3.4.
 */
class SmokeSpec :
    StringSpec({
        "module wiring compiles and the test tier runs" {
            (1 + 1) shouldBe 2
        }
    })
