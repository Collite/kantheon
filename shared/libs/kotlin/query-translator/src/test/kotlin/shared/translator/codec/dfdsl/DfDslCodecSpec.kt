package shared.translator.codec.dfdsl

import org.tatrman.dfdsl.v1.AssignExpression
import org.tatrman.dfdsl.v1.AssignOp
import org.tatrman.dfdsl.v1.FilterOp
import org.tatrman.dfdsl.v1.FromOp
import org.tatrman.dfdsl.v1.GroupByOp
import org.tatrman.dfdsl.v1.LimitOp
import org.tatrman.dfdsl.v1.Operation
import org.tatrman.dfdsl.v1.OrderByOp
import org.tatrman.dfdsl.v1.Pipeline
import org.tatrman.dfdsl.v1.SelectColumn
import org.tatrman.dfdsl.v1.SelectOp
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.TableScanNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class DfDslCodecSpec :
    StringSpec({
        val customers = qname("db", "dbo", "customers")
        val orders = qname("db", "dbo", "orders")

        "parse from-only pipeline produces a TableScan" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasTableScan() shouldBe true
            plan.tableScan.table shouldBe customers
        }

        "parse from + select + filter + limit produces correctly nested PlanNode" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setSelect(
                                SelectOp
                                    .newBuilder()
                                    .addColumns(SelectColumn.newBuilder().setName("id"))
                                    .addColumns(SelectColumn.newBuilder().setName("name")),
                            ),
                    ).addOps(
                        Operation
                            .newBuilder()
                            .setFilter(FilterOp.newBuilder().setCondition(eq(columnRef("active"), boolLit(true)))),
                    ).addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(10)))
                    .build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasLimitOffset() shouldBe true
            plan.limitOffset.limit shouldBe 10L
            plan.limitOffset.input.hasFilter() shouldBe true
            plan.limitOffset.input.filter.input
                .hasProject() shouldBe true
            plan.limitOffset.input.filter.input.project.input
                .hasTableScan() shouldBe true
        }

        "parse rejects misordered chain (filter before select)" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setFilter(FilterOp.newBuilder().setCondition(boolLit(true))),
                    ).addOps(
                        Operation
                            .newBuilder()
                            .setSelect(SelectOp.newBuilder().addColumns(SelectColumn.newBuilder().setName("id"))),
                    ).build()
            val ex = shouldThrow<DfDslParseException> { DfDslCodec.parse(pipeline) }
            ex.code shouldBe "dfdsl_chain_misordered"
        }

        "parse rejects pipeline that does not start with `from`" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(
                        Operation
                            .newBuilder()
                            .setSelect(SelectOp.newBuilder().addColumns(SelectColumn.newBuilder().setName("id"))),
                    ).build()
            val ex = shouldThrow<DfDslParseException> { DfDslCodec.parse(pipeline) }
            ex.code shouldBe "missing_from"
        }

        "parse rejects empty pipeline" {
            val ex = shouldThrow<DfDslParseException> { DfDslCodec.parse(Pipeline.getDefaultInstance()) }
            ex.code shouldBe "empty_pipeline"
        }

        "parse groupby produces an Aggregate" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setGroupby(
                                GroupByOp
                                    .newBuilder()
                                    .addKeys("region")
                                    .addAggregates(
                                        AggregateCall
                                            .newBuilder()
                                            .setFunction("sum")
                                            .addArgs(ColumnRef.newBuilder().setName("amount"))
                                            .setAlias("total"),
                                    ),
                            ),
                    ).build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasAggregate() shouldBe true
            plan.aggregate.groupKeysList shouldHaveSize 1
            plan.aggregate.aggregatesList[0].function shouldBe "sum"
        }

        "parse orderby produces a Sort" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setOrderby(
                                OrderByOp
                                    .newBuilder()
                                    .addKeys(
                                        SortKey
                                            .newBuilder()
                                            .setColumn(ColumnRef.newBuilder().setName("name"))
                                            .setDescending(true),
                                    ),
                            ),
                    ).build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasSort() shouldBe true
            plan.sort.sortKeysList[0].descending shouldBe true
        }

        "round-trip select + filter + limit" {
            val original =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setSelect(
                                SelectOp
                                    .newBuilder()
                                    .addColumns(SelectColumn.newBuilder().setName("id").setAlias("id"))
                                    .addColumns(SelectColumn.newBuilder().setName("name").setAlias("name")),
                            ),
                    ).addOps(
                        Operation
                            .newBuilder()
                            .setFilter(FilterOp.newBuilder().setCondition(eq(columnRef("active"), boolLit(true)))),
                    ).addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(10)))
                    .build()
            val plan = DfDslCodec.parse(original)
            val back = DfDslCodec.unparse(plan)
            back.opsList.map { it.opCase } shouldBe
                listOf(
                    Operation.OpCase.FROM,
                    Operation.OpCase.SELECT,
                    Operation.OpCase.FILTER,
                    Operation.OpCase.LIMIT,
                )
        }

        "round-trip assign maintains computed expressions" {
            val original =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setAssign(
                                AssignOp
                                    .newBuilder()
                                    .addExpressions(
                                        AssignExpression
                                            .newBuilder()
                                            .setAlias("doubled")
                                            .setExpression(
                                                Expression
                                                    .newBuilder()
                                                    .setFunction(
                                                        FunctionCall
                                                            .newBuilder()
                                                            .setOperation("multiply")
                                                            .addOperands(columnRef("id"))
                                                            .addOperands(intLit(2)),
                                                    ),
                                            ),
                                    ),
                            ),
                    ).build()
            val plan = DfDslCodec.parse(original)
            val back = DfDslCodec.unparse(plan)
            back.opsList.map { it.opCase } shouldBe
                listOf(Operation.OpCase.FROM, Operation.OpCase.ASSIGN)
            back.opsList[1]
                .assign.expressionsList[0]
                .alias shouldBe "doubled"
        }

        "Phase 08 B2 — parse `from_workspace` produces a WorkspaceRef leaf PlanNode" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(
                        Operation
                            .newBuilder()
                            .setFrom(FromOp.newBuilder().setWorkspaceRef("q1")),
                    ).build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasWorkspaceRef() shouldBe true
            plan.workspaceRef.workspaceName shouldBe "q1"
        }

        "Phase 08 B2 — unparse a WorkspaceRef PlanNode back into `from_workspace`" {
            val workspacePlan =
                PlanNode
                    .newBuilder()
                    .setWorkspaceRef(
                        org.tatrman.plan.v1.WorkspaceRef
                            .newBuilder()
                            .setWorkspaceName("q1"),
                    ).build()
            val pipeline = DfDslCodec.unparse(workspacePlan)
            pipeline.opsList shouldHaveSize 1
            pipeline.opsList[0].opCase shouldBe Operation.OpCase.FROM
            pipeline.opsList[0].from.sourceCase shouldBe FromOp.SourceCase.WORKSPACE_REF
            pipeline.opsList[0].from.workspaceRef shouldBe "q1"
        }

        "Phase 08 B2 — from_workspace round-trips through a select on top" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(
                        Operation
                            .newBuilder()
                            .setFrom(FromOp.newBuilder().setWorkspaceRef("session_q2")),
                    ).addOps(
                        Operation
                            .newBuilder()
                            .setSelect(SelectOp.newBuilder().addColumns(SelectColumn.newBuilder().setName("id"))),
                    ).build()
            val plan = DfDslCodec.parse(pipeline)
            plan.hasProject() shouldBe true
            plan.project.input.hasWorkspaceRef() shouldBe true
            plan.project.input.workspaceRef.workspaceName shouldBe "session_q2"

            val back = DfDslCodec.unparse(plan)
            back.opsList[0].from.workspaceRef shouldBe "session_q2"
        }

        "Phase 08 B1 — unparse a Join WITHOUT a condition is refused as join_condition_required" {
            // The Join has no condition — B1 refuses to emit a Cartesian JoinOp on the codec side
            // (B0 option (b) — enforce explicit join conditions in the codec / validator path).
            val joined =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        JoinNode
                            .newBuilder()
                            .setLeft(scan(customers))
                            .setRight(scan(orders))
                            .setJoinType(JoinType.INNER),
                    ).build()
            val ex = shouldThrow<DfDslUnparseException> { DfDslCodec.unparse(joined) }
            ex.code shouldBe "join_condition_required"
        }

        "Phase 08 B1 — parse + unparse a JoinOp round-trips through PlanNode.JoinNode" {
            val onCondition =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("customer_id")),
                            ).addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("id")),
                            ),
                    ).build()
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setJoin(
                                org.tatrman.dfdsl.v1.JoinOp
                                    .newBuilder()
                                    .setRightTable(orders)
                                    .setJoinType(JoinType.INNER)
                                    .setOn(onCondition),
                            ),
                    ).build()

            val plan = DfDslCodec.parse(pipeline)
            plan.hasJoin() shouldBe true
            plan.join.left.hasTableScan() shouldBe true
            plan.join.right.hasTableScan() shouldBe true
            plan.join.right.tableScan.table shouldBe orders
            plan.join.hasCondition() shouldBe true

            val back = DfDslCodec.unparse(plan)
            back.opsList.map { it.opCase } shouldBe listOf(Operation.OpCase.FROM, Operation.OpCase.JOIN)
            back.opsList[1].join.rightCase shouldBe org.tatrman.dfdsl.v1.JoinOp.RightCase.RIGHT_TABLE
            back.opsList[1].join.rightTable shouldBe orders
            back.opsList[1].join.joinType shouldBe JoinType.INNER
            back.opsList[1].join.on shouldBe onCondition
        }

        "Phase 08 B1 — JoinOp without `on` is refused at parse with join_condition_required" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(
                        Operation
                            .newBuilder()
                            .setJoin(
                                org.tatrman.dfdsl.v1.JoinOp
                                    .newBuilder()
                                    .setRightTable(orders),
                            ),
                    ).build()
            val ex = shouldThrow<DfDslParseException> { DfDslCodec.parse(pipeline) }
            ex.code shouldBe "join_condition_required"
        }

        "JSON parse path matches proto path" {
            val json =
                """
                {
                  "ops": [
                    {"from": {"table": {"schemaCode": "db", "namespace": "dbo", "name": "customers"}}},
                    {"limit": {"n": "5"}}
                  ]
                }
                """.trimIndent()
            val plan = DfDslCodec.parseJson(json)
            plan.hasLimitOffset() shouldBe true
            plan.limitOffset.limit shouldBe 5L
        }

        "unparseJson produces camelCase JSON" {
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .build()
            val plan = DfDslCodec.parse(pipeline)
            val out = DfDslCodec.unparseJson(plan)
            out.contains("ops") shouldBe true
            // proto3 JSON for QualifiedName field schemaCode is camelCase.
            out.contains("schemaCode") shouldBe true
        }
    })

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            if (schema ==
                "obj"
            ) {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode
                    .valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(table)).build()

private fun columnRef(name: String): Expression =
    Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

private fun intLit(v: Long): Expression =
    Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(v).setType("int")).build()

private fun boolLit(v: Boolean): Expression =
    Expression.newBuilder().setLiteral(Literal.newBuilder().setBoolValue(v).setType("bool")).build()

private fun eq(
    left: Expression,
    right: Expression,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("eq")
                .addOperands(left)
                .addOperands(right),
        ).setResultType("bool")
        .build()
