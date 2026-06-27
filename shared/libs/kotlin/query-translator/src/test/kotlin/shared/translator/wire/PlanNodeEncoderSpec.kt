package shared.translator.wire

import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework

class PlanNodeEncoderSpec :
    StringSpec({

        fun parseToRel(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "encodes a TableScan with a qualified name" {
            // SELECT * FROM customers — Calcite produces a Project over a TableScan.
            val rel = parseToRel("SELECT id, name, signup FROM customers")
            val plan = PlanNodeEncoder.encode(rel)
            // Top is a Project; its input is the TableScan.
            plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            val scan = plan.project.input
            scan.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            scan.tableScan.table.name shouldBe "customers"
        }

        "encodes a Filter with an expression condition" {
            val rel = parseToRel("SELECT id FROM customers WHERE id > 5")
            val plan = PlanNodeEncoder.encode(rel)
            plan.nodeCase shouldBe PlanNode.NodeCase.PROJECT
            val filter = plan.project.input
            filter.nodeCase shouldBe PlanNode.NodeCase.FILTER
            filter.filter.condition.function.operation shouldBe "gt"
        }

        "encodes a Join (inner)" {
            val rel =
                parseToRel(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            val plan = PlanNodeEncoder.encode(rel)
            // Walk down; somewhere there's a Join.
            var cur = plan
            while (cur.nodeCase != PlanNode.NodeCase.JOIN) {
                cur =
                    when (cur.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> cur.project.input
                        PlanNode.NodeCase.FILTER -> cur.filter.input
                        else -> error("Unexpected $cur")
                    }
            }
            cur.join.joinType shouldBe JoinType.INNER
        }

        "encodes Aggregate with COUNT" {
            val rel = parseToRel("SELECT customer_id, COUNT(*) AS n FROM orders GROUP BY customer_id")
            val plan = PlanNodeEncoder.encode(rel)
            // Top is a Project (Calcite often inserts one); the Aggregate is below.
            var cur = plan
            while (cur.nodeCase != PlanNode.NodeCase.AGGREGATE) {
                cur =
                    when (cur.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> cur.project.input
                        else -> error("Unexpected $cur")
                    }
            }
            cur.aggregate.groupKeysList shouldHaveSize 1
            cur.aggregate.aggregatesList shouldHaveSize 1
            cur.aggregate.aggregatesList[0].function shouldBe "count"
        }

        "encodes Sort + LimitOffset together" {
            val rel =
                parseToRel(
                    "SELECT id FROM customers ORDER BY id DESC OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY",
                )
            val plan = PlanNodeEncoder.encode(rel)
            // Top is Project; below is LimitOffset; below that is Sort.
            var cur = plan
            var sawLimit = false
            var sawSort = false
            while (cur.nodeCase != PlanNode.NodeCase.TABLE_SCAN) {
                cur =
                    when (cur.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> cur.project.input
                        PlanNode.NodeCase.LIMIT_OFFSET -> {
                            sawLimit = true
                            cur.limitOffset.limit shouldBe 10
                            cur.limitOffset.offset shouldBe 5
                            cur.limitOffset.input
                        }
                        PlanNode.NodeCase.SORT -> {
                            sawSort = true
                            cur.sort.sortKeysList shouldHaveSize 1
                            cur.sort.sortKeysList[0].descending shouldBe true
                            cur.sort.input
                        }
                        else -> error("Unexpected $cur")
                    }
            }
            sawLimit shouldBe true
            sawSort shouldBe true
        }

        "encoded plan round-trips through proto bytes" {
            val rel = parseToRel("SELECT id, name FROM customers WHERE id > 5")
            val plan = PlanNodeEncoder.encode(rel, emptyMap())
            val bytes = plan.toByteArray()
            val decoded = PlanNode.parseFrom(bytes)
            decoded shouldBe plan
        }

        "encodes DB table scan as TableScanNode" {
            val rel = parseToRel("SELECT id FROM customers")
            val plan = PlanNodeEncoder.encode(rel)
            var scanNode = plan
            while (scanNode.nodeCase != PlanNode.NodeCase.TABLE_SCAN && scanNode.nodeCase != PlanNode.NodeCase.SCAN) {
                scanNode =
                    when (scanNode.nodeCase) {
                        PlanNode.NodeCase.PROJECT -> scanNode.project.input
                        PlanNode.NodeCase.FILTER -> scanNode.filter.input
                        else -> error("Unexpected $scanNode")
                    }
            }
            scanNode.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            scanNode.tableScan.table.schemaCode shouldBe SchemaCode.DB
            scanNode.tableScan.table.name shouldBe "customers"
        }
    })
