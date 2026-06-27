package org.tatrman.kantheon.golem.plan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.golem.v1.MiniPlanNode
import org.tatrman.kantheon.golem.v1.PlanSource

class MiniPlanCodecSpec :
    StringSpec({

        "decodes a PATTERN plan with query + render nodes" {
            val raw =
                """
                {"source":"PATTERN","confidence":0.95,"rationale":"hit",
                 "nodes":[
                   {"node_id":"q1","query":{"source":"listUnpaidInvoices","source_language":"transdsl",
                     "params_json":"{\"customerId\":\"1\"}","pattern_id":"listUnpaidInvoices","compile_first":false}},
                   {"node_id":"r1","render":{"kind_hint":"TABLE","input_node_ids":["q1"],"caption":"Faktury"}}
                 ]}
                """.trimIndent()
            val plan = MiniPlanCodec.decode(raw)
            plan.source shouldBe PlanSource.PATTERN
            plan.confidence shouldBe 0.95
            plan.nodesCount shouldBe 2
            plan.getNodes(0).kindCase shouldBe MiniPlanNode.KindCase.QUERY
            plan.getNodes(0).query.patternId shouldBe "listUnpaidInvoices"
            plan.getNodes(1).render.kindHint shouldBe FormatKind.TABLE
            plan.getNodes(1).render.caption shouldBe "Faktury"
        }

        "decodes a FREE_SQL plan" {
            val raw =
                """{"source":"FREE_SQL","confidence":0.7,"rationale":"no pattern",
                   "nodes":[{"node_id":"q1","query":{"source":"SELECT 1","source_language":"sql",
                   "params_json":"{}","compile_first":true}}]}"""
            val plan = MiniPlanCodec.decode(raw)
            plan.source shouldBe PlanSource.FREE_SQL
            plan.getNodes(0).query.compileFirst shouldBe true
        }

        "decodes a CLARIFICATION plan with no nodes" {
            val plan =
                MiniPlanCodec.decode(
                    """{"source":"CLARIFICATION","confidence":0.4,"rationale":"ambiguous","nodes":[]}""",
                )
            plan.source shouldBe PlanSource.CLARIFICATION
            plan.nodesCount shouldBe 0
        }

        "strips a ```json fence before decoding" {
            val fenced = "```json\n{\"source\":\"AMEND\",\"confidence\":0.9,\"nodes\":[]}\n```"
            MiniPlanCodec.decode(fenced).source shouldBe PlanSource.AMEND
        }

        "normalises params_json given as an inline object" {
            val raw =
                """{"source":"PATTERN","confidence":0.9,"nodes":[
                   {"node_id":"q1","query":{"source":"p","source_language":"transdsl",
                   "params_json":{"customerId":"1"},"pattern_id":"p"}}]}"""
            MiniPlanCodec
                .decode(raw)
                .getNodes(0)
                .query.paramsJson shouldBe """{"customerId":"1"}"""
        }

        "surfaces losing_plan_summary" {
            val raw = """{"source":"PATTERN","confidence":0.9,"losing_plan_summary":"free-sql","nodes":[]}"""
            MiniPlanCodec.decode(raw).losingPlanSummary shouldBe "free-sql"
        }

        "rejects an unknown source value" {
            shouldThrow<PlanDecodeException> {
                MiniPlanCodec.decode(
                    """{"source":"BOGUS","confidence":0.9,"nodes":[]}""",
                )
            }
        }

        "rejects a missing source field" {
            shouldThrow<PlanDecodeException> { MiniPlanCodec.decode("""{"confidence":0.9,"nodes":[]}""") }
        }

        "rejects non-JSON content" {
            shouldThrow<PlanDecodeException> { MiniPlanCodec.decode("sorry, I cannot") }
        }
    })
