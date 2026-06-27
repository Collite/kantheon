package org.tatrman.kantheon.golem.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.golem.v1.GolemRequest
import org.tatrman.kantheon.golem.v1.PlanSource
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding
import org.tatrman.kantheon.themis.v1.Themis.IntentKind
import org.tatrman.kantheon.themis.v1.Themis.Resolution
import org.tatrman.kantheon.themis.v1.Themis.UniversalEntityBinding
import org.tatrman.kantheon.themis.v1.Themis.UniversalEntityType

private fun universal(
    type: UniversalEntityType,
    value: String,
): EntityBinding =
    EntityBinding
        .newBuilder()
        .setUniversal(UniversalEntityBinding.newBuilder().setEntityType(type).setNormalizedValue(value))
        .build()

class KindInferenceAndInvestigateSpec :
    StringSpec({

        // ---- KindInference precedence -------------------------------------------
        "a pattern result_kind_hint='chart' wins → bar chart" {
            val k = KindInference.infer("chart", PlanSource.PATTERN, emptyList(), emptyList(), chartOnCompare = true)
            k.kind shouldBe FormatKind.CHART
            k.chartType shouldBe "bar"
        }

        "no hint, no amend → table" {
            KindInference.infer(null, PlanSource.PATTERN, emptyList(), emptyList(), chartOnCompare = true).kind shouldBe
                FormatKind.TABLE
        }

        "an explicit result_kind_hint='table' suppresses amend-on-compare" {
            val prior = listOf(universal(UniversalEntityType.DATE, "2026.01"))
            val current = listOf(universal(UniversalEntityType.DATE, "2026.02"))
            KindInference.infer("table", PlanSource.AMEND, current, prior, chartOnCompare = true).kind shouldBe
                FormatKind.TABLE
        }

        "an UNRECOGNIZED hint is ignored — it falls through to amend-on-compare, not a forced table" {
            val prior = listOf(universal(UniversalEntityType.DATE, "2026.01"))
            val current =
                listOf(
                    universal(UniversalEntityType.DATE, "2026.01"),
                    universal(UniversalEntityType.DATE, "2026.02"),
                )
            // "barchart" is not a known kind token — must not lock the turn to a table.
            val k = KindInference.infer("barchart", PlanSource.AMEND, current, prior, chartOnCompare = true)
            k.kind shouldBe FormatKind.CHART
            k.seriesField shouldBe "DATE"
        }

        "amend-on-compare: a second value for a shared axis fires a chart keyed on that axis" {
            val prior = listOf(universal(UniversalEntityType.DATE, "2026.01"))
            val current =
                listOf(
                    universal(UniversalEntityType.DATE, "2026.01"),
                    universal(UniversalEntityType.DATE, "2026.02"),
                )
            val k = KindInference.infer(null, PlanSource.AMEND, current, prior, chartOnCompare = true)
            k.kind shouldBe FormatKind.CHART
            k.seriesField shouldBe "DATE"
        }

        "amend-on-compare respects the gate flag (off → table)" {
            val prior = listOf(universal(UniversalEntityType.DATE, "2026.01"))
            val current = listOf(universal(UniversalEntityType.DATE, "2026.02"))
            KindInference.infer(null, PlanSource.AMEND, current, prior, chartOnCompare = false).kind shouldBe
                FormatKind.TABLE
        }

        // ---- InvestigateChip (PD-1) ---------------------------------------------
        "an analytical intent that fails the gate gets an InvestigateChip with a filled handoff" {
            val req =
                GolemRequest
                    .newBuilder()
                    .setId("t1")
                    .setGolemId("golem-erp")
                    .setQuestion("proč klesly tržby?")
                    .setResolvedIntent(Resolution.newBuilder().setIntentKind(IntentKind.RCA))
                    .build()
            val chip = InvestigateChips.maybe(req, gateFailed = true, currentView = null)
            chip.shouldNotBeNull()
            chip.hasInvestigate() shouldBe true
            chip.investigate.handoff.sourceAgentId shouldBe "golem-erp"
            chip.investigate.handoff.userQuestion shouldBe "proč klesly tržby?"
            chip.investigate.proposedQuestion shouldBe "proč klesly tržby?"
        }

        "a procedural intent (or a passing gate) gets no InvestigateChip" {
            val procedural =
                GolemRequest
                    .newBuilder()
                    .setResolvedIntent(Resolution.newBuilder().setIntentKind(IntentKind.PROCEDURAL))
                    .build()
            InvestigateChips.maybe(procedural, gateFailed = true, currentView = null).shouldBeNull()

            val analyticalButPassed =
                GolemRequest
                    .newBuilder()
                    .setResolvedIntent(Resolution.newBuilder().setIntentKind(IntentKind.RCA))
                    .build()
            InvestigateChips.maybe(analyticalButPassed, gateFailed = false, currentView = null).shouldBeNull()
        }
    })
