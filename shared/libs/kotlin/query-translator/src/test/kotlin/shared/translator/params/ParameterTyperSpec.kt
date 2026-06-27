package shared.translator.params

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexShuttle
import org.apache.calcite.sql.type.SqlTypeName
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework

class ParameterTyperSpec :
    StringSpec({

        fun preparedFor(
            sql: String,
            params: List<SqlParam>,
        ): Pair<String, PreparedSql> {
            val prepared = ParameterBridge.prepareSqlForCalcite(sql, params)
            return prepared.sql to prepared
        }

        fun parse(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        /** Collect every [RexDynamicParam] in the tree, breadth-first across rels. */
        fun collectParams(rel: org.apache.calcite.rel.RelNode): List<RexDynamicParam> {
            val out = mutableListOf<RexDynamicParam>()
            val shuttle =
                object : RexShuttle() {
                    override fun visitDynamicParam(d: RexDynamicParam): RexNode {
                        out.add(d)
                        return d
                    }
                }

            // Walk each rel in the tree; RelNode.accept(RexShuttle) only visits the rel's own
            // exprs, so descend manually.
            fun visit(n: org.apache.calcite.rel.RelNode) {
                n.accept(shuttle)
                n.inputs.forEach(::visit)
            }
            visit(rel)
            return out
        }

        "applyTypes re-types a RexDynamicParam to the declared type (BIGINT)" {
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE id = {cid}",
                    listOf(SqlParam("cid", "int", 7)),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].index shouldBe 0
            params[0].type.sqlTypeName shouldBe SqlTypeName.BIGINT
        }

        "applyTypes is a no-op when the SqlParam list is empty" {
            val rel = parse("SELECT id FROM customers WHERE id = 5")
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten =
                ParameterTyper.applyTypes(
                    rel = rel,
                    parameterOrder = emptyList(),
                    valuesByName = emptyMap(),
                    typeFactory = typeFactory,
                )
            rewritten shouldBe rel // identity when no params
        }

        "applyTypes re-types a string parameter to VARCHAR" {
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            val rewritten = ParameterTyper.applyTypes(rel, prepared, typeFactory)

            val params = collectParams(rewritten)
            params.size shouldBe 1
            params[0].type.sqlTypeName shouldBe SqlTypeName.VARCHAR
        }

        "applyTypes leaves params alone when the index isn't in parameterOrder" {
            // A prepared SQL with one param but the rel has two ? — the second isn't covered;
            // it should keep whatever type Calcite inferred (not be rewritten or crash).
            val (cleanedSql, prepared) =
                preparedFor(
                    "SELECT id FROM customers WHERE id = {a}",
                    listOf(SqlParam("a", "int", 1)),
                )
            val rel = parse(cleanedSql)
            val typeFactory = TranslatorFramework(FixtureModel.handle()).newRelBuilder().typeFactory
            // Shrink parameterOrder to empty: applyTypes returns the rel verbatim by contract.
            val rewritten =
                ParameterTyper.applyTypes(
                    rel = rel,
                    parameterOrder = emptyList(),
                    valuesByName = prepared.values,
                    typeFactory = typeFactory,
                )
            rewritten shouldBe rel
        }
    })
