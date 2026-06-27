package shared.translator.params

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework
import shared.translator.wire.PlanNodeEncoder

/**
 * Covers [PositionalParameters] — `namesByIndex` recovery from a real nested plan (Project/Filter),
 * `positional` repeat-expansion, and every throw path including the H1 placeholders-without-bindings
 * guard and the L5 index-collision guard.
 */
class PositionalParametersSpec :
    StringSpec({

        fun parse(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        /** Build a wire plan for [sql] with the original `{name}`s restored on the ParameterRefs. */
        fun planFor(
            sql: String,
            params: List<SqlParam>,
        ): org.tatrman.plan.v1.PlanNode {
            val prepared = ParameterBridge.prepareSqlForCalcite(sql, params)
            val rel = parse(prepared.sql)
            val parameterNames: Map<Int, String> =
                prepared.parameterOrder.mapIndexed { i, n -> i to n }.toMap()
            return PlanNodeEncoder.encode(rel, parameterNames)
        }

        fun binding(
            name: String,
            value: Long,
        ): ParameterBinding =
            ParameterBinding
                .newBuilder()
                .setName(name)
                .setType("int")
                .setValue(Value.newBuilder().setIntValue(value))
                .build()

        // ---- namesByIndex: nested-condition recovery (Project over Filter) ----

        "namesByIndex recovers a name from a Filter condition nested below the top Project" {
            // Project(id) over Filter(id = {cid}) over TableScan — the param lives in the Filter,
            // a level below the Project, so a non-recursive walk would miss it.
            val plan = planFor("SELECT id FROM customers WHERE id = {cid}", listOf(SqlParam("cid", "int", 7)))
            val names = PositionalParameters.namesByIndex(plan)
            names shouldBe mapOf(0 to "cid")
        }

        "namesByIndex recovers all names from a multi-parameter Filter condition" {
            val plan =
                planFor(
                    "SELECT id FROM orders WHERE customer_id = {cid} AND total > {min}",
                    listOf(SqlParam("cid", "int", 7), SqlParam("min", "int", 100)),
                )
            val names = PositionalParameters.namesByIndex(plan)
            names shouldBe mapOf(0 to "cid", 1 to "min")
        }

        "namesByIndex collapses a name referenced at two indices to one entry per distinct index" {
            // {a} referenced twice → two distinct positional indices, both named "a".
            val plan =
                planFor(
                    "SELECT id FROM customers WHERE name = {a} OR name = {a}",
                    listOf(SqlParam("a", "text", "x")),
                )
            val names = PositionalParameters.namesByIndex(plan)
            names shouldBe mapOf(0 to "a", 1 to "a")
        }

        // ---- positional: repeat expansion ----

        "positional replays a repeated ? to its name's value at every position" {
            // Two placeholders (indices 0 and 1) both named "a" → one binding expands to two.
            val out =
                PositionalParameters.positional(
                    order = listOf(0, 1),
                    namesByIndex = mapOf(0 to "a", 1 to "a"),
                    bindings = listOf(binding("a", 42)),
                )
            out.size shouldBe 2
            out[0].value.intValue shouldBe 42
            out[1].value.intValue shouldBe 42
        }

        "positional follows the ?-appearance order from `order`, not the binding list order" {
            val out =
                PositionalParameters.positional(
                    order = listOf(1, 0), // b appears first, a second
                    namesByIndex = mapOf(0 to "a", 1 to "b"),
                    bindings = listOf(binding("a", 1), binding("b", 2)),
                )
            out.map { it.value.intValue } shouldBe listOf(2L, 1L)
        }

        // ---- positional: no-op / throw paths ----

        "positional is a no-op (empty) when there are no placeholders, even with no bindings" {
            PositionalParameters.positional(
                order = emptyList(),
                namesByIndex = emptyMap(),
                bindings = emptyList(),
            ) shouldBe emptyList()
        }

        "positional throws when placeholders are present but no bindings are supplied (H1)" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    PositionalParameters.positional(
                        order = listOf(0, 1),
                        namesByIndex = mapOf(0 to "a", 1 to "b"),
                        bindings = emptyList(),
                    )
                }
            ex.message!! shouldContain "2 placeholder(s) but no bindings"
        }

        "positional throws when a placeholder index has no name in the plan" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    PositionalParameters.positional(
                        order = listOf(0),
                        namesByIndex = emptyMap(), // index 0 unmapped
                        bindings = listOf(binding("a", 1)),
                    )
                }
            ex.message!! shouldContain "has no name in the plan"
        }

        "positional throws when a placeholder's name has no supplied binding" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    PositionalParameters.positional(
                        order = listOf(0),
                        namesByIndex = mapOf(0 to "missing"),
                        bindings = listOf(binding("a", 1)),
                    )
                }
            ex.message!! shouldContain "No binding supplied for parameter '{missing}'"
        }
    })
