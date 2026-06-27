package org.tatrman.kantheon.report.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

/**
 * Stage 3.5 T1/T2/T5 — the Midas `investment-overview:v1` dashboard content parses into the
 * generic template model, and a `{client, portfolio, period}` fill resolves every pane's
 * ViewProvenance source (the `{portfolio_id}` / `{period.end}` interpolation).
 */
class DashboardTemplateSpec :
    StringSpec({

        "the bundled investment-overview:v1 content parses into the generic template model" {
            val t = DashboardTemplateLoader.fromClasspath("dashboard-templates/investment-overview.v1.yaml")!!
            t.templateId shouldBe "investment-overview:v1"
            t.params.map { it.name } shouldBe listOf("client_id", "portfolio_id", "period")
            t.panes.map { it.kind } shouldBe listOf("CHART", "TABLE", "REPORT_PREVIEW")
            // pane sources are ViewProvenance, not agent_call_spec
            t.panes[0].source["provenance_kind"] shouldBe "AGENT_TURN"
            t.panes[1].source["provenance_kind"] shouldBe "TOOL_CALL"
            t.panes[2].source["provenance_kind"] shouldBe "REPORT_RENDER"
        }

        "create-from-template resolves every pane's params (portfolio_id + derived period.end)" {
            val t = DashboardTemplateLoader.fromClasspath("dashboard-templates/investment-overview.v1.yaml")!!
            val panes =
                DashboardParamResolver.resolve(
                    t,
                    args = mapOf("client_id" to "c1", "portfolio_id" to "p-smith"),
                    today = LocalDate.of(2026, 6, 27),
                )
            panes shouldHaveSize 3
            // chart question interpolates portfolio + the defaulted period (ytd)
            panes[0].source["question"] shouldBe "ytd performance for portfolio p-smith"
            // table tool args carry portfolio + period.end = today (ytd is relative)
            panes[1].source["args_json"]!! shouldContain "\"portfolio_id\":\"p-smith\""
            panes[1].source["args_json"]!! shouldContain "\"as_of\":\"2026-06-27\""
            // report-preview proxies the portfolio statement with the bound params
            panes[2].source["template_id"] shouldBe "portfolio-statement:v1"
            panes[2].source["args_json"]!! shouldContain "\"portfolio_id\":\"p-smith\""
        }

        "an explicit period range resolves period.end to the range end" {
            val t = DashboardTemplateLoader.fromClasspath("dashboard-templates/investment-overview.v1.yaml")!!
            val panes =
                DashboardParamResolver.resolve(
                    t,
                    args = mapOf("client_id" to "c1", "portfolio_id" to "p1", "period" to "2026-01-01..2026-03-31"),
                )
            panes[1].source["args_json"]!! shouldContain "\"as_of\":\"2026-03-31\""
        }
    })
