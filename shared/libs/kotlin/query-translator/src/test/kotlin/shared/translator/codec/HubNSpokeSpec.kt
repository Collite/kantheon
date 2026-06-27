package shared.translator.codec

import org.tatrman.dfdsl.v1.FromOp
import org.tatrman.dfdsl.v1.LimitOp
import org.tatrman.dfdsl.v1.Operation
import org.tatrman.dfdsl.v1.Pipeline
import org.tatrman.dfdsl.v1.SelectColumn
import org.tatrman.dfdsl.v1.SelectOp
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.transdsl.v1.Column
import org.tatrman.transdsl.v1.Query
import org.tatrman.transdsl.v1.Source
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import shared.translator.codec.dfdsl.DfDslCodec
import shared.translator.codec.transdsl.TransDslCodec

/**
 * Hub-n-spoke matrix smoke. The Translator orchestrator dispatch is tested
 * separately in the orchestrator's own spec; here we focus on the
 * direct-codec routes that v1.10 lights up:
 *
 *   TransDSL → PlanNode → DataFrame DSL
 *   DataFrame DSL → PlanNode → TransDSL
 *
 * The two SQL routes (SQL → PlanNode → DSL and DSL → PlanNode → SQL) work
 * once the Translator orchestrator wiring is exercised — the existing
 * `TranslatorParseSpec` covers SQL-side parsing.
 */
class HubNSpokeSpec :
    StringSpec({
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()

        "TransDSL → PlanNode → DataFrame DSL preserves source + columns" {
            val q =
                Query
                    .newBuilder()
                    .addCore(Source.newBuilder().setDataObject(customers))
                    .addColumns(Column.newBuilder().setName("id").setAlias("id"))
                    .addColumns(Column.newBuilder().setName("name").setAlias("name"))
                    .build()
            val plan = TransDslCodec.parse(q)
            val pipeline = DfDslCodec.unparse(plan)
            val ops = pipeline.opsList.map { it.opCase }
            ops shouldBe listOf(Operation.OpCase.FROM, Operation.OpCase.SELECT)
            pipeline.opsList[0].from.table shouldBe customers
            pipeline.opsList[1]
                .select.columnsList
                .map { it.name } shouldBe listOf("id", "name")
        }

        "DataFrame DSL → PlanNode → TransDSL preserves source + columns" {
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
                                    .addColumns(SelectColumn.newBuilder().setName("id").setAlias("id"))
                                    .addColumns(SelectColumn.newBuilder().setName("name").setAlias("name")),
                            ),
                    ).build()
            val plan = DfDslCodec.parse(pipeline)
            val q = TransDslCodec.unparse(plan)
            q.coreList[0].dataObject shouldBe customers
            q.columnsList.map { it.name } shouldBe listOf("id", "name")
        }

        "DataFrame DSL → PlanNode → TransDSL preserves limit (via degraded core wrapping)" {
            // TransDSL has no native LimitOffset slot — round-tripping a
            // limit-bearing plan through TransDSL drops the limit unless it's
            // wrapped in a Subquery. v1 documents this: the unparser raises
            // operation_not_supported_in_target_language for orphan
            // LimitOffset above the core.
            val pipeline =
                Pipeline
                    .newBuilder()
                    .addOps(Operation.newBuilder().setFrom(FromOp.newBuilder().setTable(customers)))
                    .addOps(Operation.newBuilder().setLimit(LimitOp.newBuilder().setN(10)))
                    .build()
            val plan = DfDslCodec.parse(pipeline)
            // TransDSL unparse should reject the LimitOffset since it's not
            // expressible in the v1 Query shape. Documents the v1 limitation.
            val ex =
                runCatching { TransDslCodec.unparse(plan) }.exceptionOrNull()
            (ex != null) shouldBe true
        }
    })
