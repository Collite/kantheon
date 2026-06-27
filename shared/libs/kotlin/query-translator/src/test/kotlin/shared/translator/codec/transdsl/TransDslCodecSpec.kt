package shared.translator.codec.transdsl

import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.transdsl.v1.AggregateColumn
import org.tatrman.transdsl.v1.Aggregation
import org.tatrman.transdsl.v1.Calculation
import org.tatrman.transdsl.v1.Column
import org.tatrman.transdsl.v1.Query
import org.tatrman.transdsl.v1.Source
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TransDslCodecSpec :
    StringSpec({
        val customers = qname("db", "dbo", "customers")

        "parse simple data_object + columns produces Project over TableScan" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addColumns(Column.newBuilder().setName("id").setAlias("id"))
                    .addColumns(Column.newBuilder().setName("name").setAlias("name"))
                    .build()
            val plan = TransDslCodec.parse(q)
            plan.hasProject() shouldBe true
            plan.project.input.hasTableScan() shouldBe true
            plan.project.expressionsList shouldHaveSize 2
        }

        "parse with calculation puts both columns and calculations into one Project" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addColumns(Column.newBuilder().setName("id"))
                    .addCalculations(
                        Calculation
                            .newBuilder()
                            .setAlias("doubled")
                            .setExpression(
                                Expression
                                    .newBuilder()
                                    .setFunction(
                                        FunctionCall
                                            .newBuilder()
                                            .setOperation("multiply")
                                            .addOperands(
                                                Expression.newBuilder().setColumnRef(
                                                    ColumnRef.newBuilder().setName("id"),
                                                ),
                                            ).addOperands(
                                                Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(2)),
                                            ),
                                    ),
                            ),
                    ).build()
            val plan = TransDslCodec.parse(q)
            plan.project.expressionsList shouldHaveSize 2
            plan.project.expressionsList[1].alias shouldBe "doubled"
        }

        "filter with no aggregation becomes a Filter wrapping the source" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .setFilter(
                        eq(columnRef("active"), boolLit(true)),
                    ).build()
            val plan = TransDslCodec.parse(q)
            plan.hasFilter() shouldBe true
            plan.filter.input.hasTableScan() shouldBe true
        }

        "filter referencing an aggregated alias becomes HAVING (above Aggregate)" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .setAggregation(
                        Aggregation
                            .newBuilder()
                            .addGroup("region")
                            .addCount(AggregateColumn.newBuilder().setName("id").setAlias("cnt")),
                    ).setFilter(eq(columnRef("cnt"), intLit(5)))
                    .build()
            val plan = TransDslCodec.parse(q)
            plan.hasFilter() shouldBe true
            plan.filter.input.hasAggregate() shouldBe true
        }

        "filter not referencing aggregate stays below Aggregate (WHERE)" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .setAggregation(Aggregation.newBuilder().addGroup("region"))
                    .setFilter(eq(columnRef("region"), stringLit("EU")))
                    .build()
            val plan = TransDslCodec.parse(q)
            plan.hasAggregate() shouldBe true
            plan.aggregate.input.hasFilter() shouldBe true
        }

        "multiple cores fold into a Cartesian Join chain when a filter is supplied" {
            // Phase 08 B3 — the codec still produces the left-deep Join; the joining condition
            // travels inside the `filter` (B0 option (b): no separate Joiner; the filter sits
            // above the Cartesian and is folded into the join's `condition` by the validator).
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addCore(Source.newBuilder().setDataObject(qname("db", "dbo", "orders")))
                    .setFilter(eq(columnRef("customer_id"), columnRef("id")))
                    .build()
            val plan = TransDslCodec.parse(q)
            plan.hasFilter() shouldBe true
            plan.filter.input.hasJoin() shouldBe true
            plan.filter.input.join.joinType shouldBe JoinType.INNER
        }

        "Phase 08 B3 — multi-core Query without a filter is rejected (no silent Cartesian)" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addCore(Source.newBuilder().setDataObject(qname("db", "dbo", "orders")))
                    .build()
            val ex =
                shouldThrow<TransDslParseException> {
                    TransDslCodec.parse(q)
                }
            ex.code shouldBe "join_condition_required"
            ex.message!!.contains("Cartesian") shouldBe true
        }

        "missing core throws structured parse exception" {
            val ex =
                shouldThrow<TransDslParseException> {
                    TransDslCodec.parse(Query.newBuilder().build())
                }
            ex.code shouldBe "missing_core"
        }

        "unparse(parse(query)) round-trips columns + filter" {
            val original =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addColumns(Column.newBuilder().setName("id"))
                    .addColumns(Column.newBuilder().setName("name"))
                    .setFilter(eq(columnRef("active"), boolLit(true)))
                    .build()
            val plan = TransDslCodec.parse(original)
            val back = TransDslCodec.unparse(plan)
            back.coreList shouldHaveSize 1
            back.coreList[0].dataObject shouldBe customers
            back.columnsList shouldHaveSize 2
            back.hasFilter() shouldBe true
        }

        "unparse(parse(...)) round-trips aggregation + group keys" {
            val original =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .setAggregation(
                        Aggregation
                            .newBuilder()
                            .addGroup("region")
                            .addSum(AggregateColumn.newBuilder().setName("amount").setAlias("total")),
                    ).build()
            val plan = TransDslCodec.parse(original)
            val back = TransDslCodec.unparse(plan)
            back.aggregation.groupList shouldBe listOf("region")
            back.aggregation.sumList shouldHaveSize 1
            back.aggregation.sumList[0].alias shouldBe "total"
        }

        "JSON parse path produces the same plan as the proto path" {
            val json =
                """
                {
                  "core": [{"dataObject": {"schemaCode": "db", "namespace": "dbo", "name": "customers"}}],
                  "columns": [{"name": "id"}]
                }
                """.trimIndent()
            val plan = TransDslCodec.parseJson(json)
            plan.hasProject() shouldBe true
            plan.project.input.hasTableScan() shouldBe true
        }

        "unparseJson emits camelCase JSON keys (proto3 default)" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setTableScan(
                        org.tatrman.plan.v1
                            .TableScanNode
                            .newBuilder()
                            .setTable(customers),
                    ).build()
            val out = TransDslCodec.unparseJson(plan)
            out.contains("schemaCode") shouldBe true
            out.contains("dataObject") shouldBe true
        }

        "unparse rejects a non-INNER join with structured error" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        org.tatrman.plan.v1
                            .ProjectNode
                            .newBuilder()
                            .setInput(
                                PlanNode
                                    .newBuilder()
                                    .setJoin(
                                        org.tatrman.plan.v1
                                            .JoinNode
                                            .newBuilder()
                                            .setLeft(scan(customers))
                                            .setRight(scan(qname("db", "dbo", "orders")))
                                            .setJoinType(JoinType.LEFT),
                                    ),
                            ).addExpressions(
                                org.tatrman.plan.v1
                                    .NamedExpression
                                    .newBuilder()
                                    .setExpression(columnRef("id"))
                                    .setAlias("id"),
                            ),
                    ).build()
            val ex =
                shouldThrow<TransDslUnparseException> {
                    TransDslCodec.unparse(plan)
                }
            ex.code shouldBe "operation_not_supported_in_target_language"
        }

        // ----- Phase 2.4 — workspace_ref round-trip -----

        "parse Source.workspace_ref produces a PlanNode.workspace_ref leaf" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setWorkspaceRef("q1"))
                    .addColumns(Column.newBuilder().setName("id"))
                    .build()
            val plan = TransDslCodec.parse(q)
            // The codec wraps the leaf in a Project for the columns list,
            // so we descend through Project to find the workspace_ref leaf.
            plan.hasProject() shouldBe true
            plan.project.input.hasWorkspaceRef() shouldBe true
            plan.project.input.workspaceRef.workspaceName shouldBe "q1"
        }

        "unparse PlanNode.workspace_ref back to TransDSL Source.workspace_ref" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setWorkspaceRef(
                        org.tatrman.plan.v1.WorkspaceRef
                            .newBuilder()
                            .setWorkspaceName("q2"),
                    ).build()
            val q = TransDslCodec.unparse(plan)
            q.coreList shouldHaveSize 1
            q.coreList[0].sourceKindCase shouldBe Source.SourceKindCase.WORKSPACE_REF
            q.coreList[0].workspaceRef shouldBe "q2"
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
    PlanNode
        .newBuilder()
        .setTableScan(
            org.tatrman.plan.v1.TableScanNode
                .newBuilder()
                .setTable(table),
        ).build()

private fun columnRef(name: String): Expression =
    Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

private fun intLit(v: Long): Expression =
    Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(v).setType("int")).build()

private fun stringLit(v: String): Expression =
    Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue(v).setType("text")).build()

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

@Suppress("unused")
private fun unusedAggregateNode() = AggregateNode.getDefaultInstance()
