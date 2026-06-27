package shared.translator.joiner

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import shared.translator.framework.FixtureModel
import shared.translator.framework.InMemoryModelHandle
import shared.translator.framework.ModelForeignKey
import shared.translator.wire.Expressions

class JoinerPhysicalSpec :
    StringSpec({

        fun colQname(
            table: String,
            column: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("$table.$column")
                .build()

        fun dbScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(qname))
                .build()

        fun unconditionedJoin(
            left: PlanNode,
            right: PlanNode,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setJoin(
                    JoinNode
                        .newBuilder()
                        .setLeft(left)
                        .setRight(right)
                        .setJoinType(JoinType.INNER),
                ).build()

        // FK: orders.customer_id → customers.id
        val customerToOrdersFk =
            ModelForeignKey(
                from = listOf(colQname("orders", "customer_id")),
                to = listOf(colQname("customers", "id")),
            )

        "two tables, one FK → join condition inserted" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val result = JoinerPhysical.apply(input, model)

            result.warnings.shouldBeEmpty()
            val joined = result.plan.join
            joined.hasCondition() shouldBe true
            val cond = joined.condition
            cond.function.operation shouldBe "eq"
            val leftOperand = cond.function.operandsList[0]
            val rightOperand = cond.function.operandsList[1]
            // FK is `orders.customer_id → customers.id`. customers is on the left of the join
            // so the left operand carries the `id` column with $L source-alias.
            leftOperand.columnRef.name shouldBe "id"
            leftOperand.columnRef.sourceAlias shouldBe Expressions.LEFT_INPUT_TAG
            rightOperand.columnRef.name shouldBe "customer_id"
            rightOperand.columnRef.sourceAlias shouldBe Expressions.RIGHT_INPUT_TAG
        }

        "two tables, no FK → Cartesian preserved with NoRelation warning" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = emptyList(),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val result = JoinerPhysical.apply(input, model)

            result.warnings shouldHaveSize 1
            result.warnings.single().shouldBeInstanceOf<JoinerWarning.NoRelation>()
            result.plan.join.hasCondition() shouldBe false
        }

        "already-conditioned join → untouched (don't double-join)" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val preset =
                Expression
                    .newBuilder()
                    .setLiteral(
                        org.tatrman.plan.v1.Literal
                            .newBuilder()
                            .setBoolValue(true),
                    ).build()
            val input =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        JoinNode
                            .newBuilder()
                            .setLeft(dbScan(FixtureModel.customersQname))
                            .setRight(dbScan(FixtureModel.ordersQname))
                            .setJoinType(JoinType.INNER)
                            .setCondition(preset),
                    ).build()

            val result = JoinerPhysical.apply(input, model)

            result.plan shouldBe input
            result.warnings.shouldBeEmpty()
        }

        "idempotent — running twice produces the same tree" {
            val model =
                InMemoryModelHandle(
                    tables = listOf(FixtureModel.customers, FixtureModel.orders),
                    foreignKeys = listOf(customerToOrdersFk),
                )
            val input =
                unconditionedJoin(
                    dbScan(FixtureModel.customersQname),
                    dbScan(FixtureModel.ordersQname),
                )

            val first = JoinerPhysical.apply(input, model).plan
            val second = JoinerPhysical.apply(first, model).plan

            second shouldBe first
        }
    })
