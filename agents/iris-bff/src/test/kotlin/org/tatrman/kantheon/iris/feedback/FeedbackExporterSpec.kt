package org.tatrman.kantheon.iris.feedback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.kantheon.iris.domain.FeedbackRecord
import java.util.UUID

class FeedbackExporterSpec :
    StringSpec({

        "groups verdicts under the answering agent corpus with the question" {
            val t = UUID.randomUUID()
            val rows = listOf(FeedbackRecord(t, "u1", "golem-erp", verdict = "down", reason = "wrong_data"))
            val out = FeedbackExporter.export(rows) { "Revenue by month?" }
            out.keys shouldBe setOf("golem-erp")
            out["golem-erp"]!!.single().let {
                it shouldContain "\"verdict\":\"down\""
                it shouldContain "wrong_data"
                it shouldContain "Revenue by month?"
            }
        }

        "a corrected_agent_id emits a Themis routing label" {
            val t = UUID.randomUUID()
            val rows =
                listOf(
                    FeedbackRecord(
                        t,
                        "u1",
                        "golem-erp",
                        verdict = "down",
                        reason = "wrong_agent",
                        correctedAgentId = "golem-sales",
                    ),
                )
            val out = FeedbackExporter.export(rows) { "Show me the pipeline" }
            out.keys shouldBe setOf("golem-erp", "themis")
            out["themis"]!!.single().let {
                it shouldContain "\"wrong_agent\":\"golem-erp\""
                it shouldContain "\"correct_agent\":\"golem-sales\""
                it shouldContain "Show me the pipeline"
            }
        }

        "skips the routing label when the turn (question) is gone" {
            val t = UUID.randomUUID()
            val rows = listOf(FeedbackRecord(t, "u1", "golem-erp", verdict = "down", correctedAgentId = "golem-sales"))
            val out = FeedbackExporter.export(rows) { null }
            out.containsKey("themis") shouldBe false
            out.containsKey("golem-erp") shouldBe true
        }
    })
