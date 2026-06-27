package shared.translator.params

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ParameterBridgeSpec :
    StringSpec({

        "single parameter rewrites to one ? placeholder" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {a}",
                    listOf(SqlParam("a", "int", 42)),
                )
            r.sql shouldBe "SELECT id FROM customers WHERE id = ?"
            r.parameterOrder shouldBe listOf("a")
            r.distinctNames shouldBe listOf("a")
        }

        "same name twice produces two ? but a single value entry (identity-by-name)" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE id = {a} OR alt_id = {a}",
                    listOf(SqlParam("a", "int", 42)),
                )
            r.sql shouldBe "SELECT id FROM customers WHERE id = ? OR alt_id = ?"
            r.parameterOrder shouldBe listOf("a", "a")
            r.distinctNames shouldBe listOf("a")
            r.values["a"]!!.value shouldBe 42
        }

        "two distinct names appear in left-to-right order" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM orders WHERE customer_id = {cid} AND total > {min}",
                    listOf(
                        SqlParam("cid", "int", 7),
                        SqlParam("min", "float", 100.0),
                    ),
                )
            r.parameterOrder shouldBe listOf("cid", "min")
            r.distinctNames shouldBe listOf("cid", "min")
        }

        "distinct names dedupe to first-appearance order" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "WHERE x = {a} OR y = {b} OR z = {a}",
                    listOf(SqlParam("a", "int", 1), SqlParam("b", "int", 2)),
                )
            r.parameterOrder shouldBe listOf("a", "b", "a")
            r.distinctNames shouldBe listOf("a", "b")
        }

        "unknown parameter reference fails fast" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    ParameterBridge.prepareSqlForCalcite(
                        "WHERE id = {missing}",
                        listOf(SqlParam("a", "int", 42)),
                    )
                }
            ex.message!!.contains("missing") shouldBe true
        }

        "no parameters → SQL passes through unchanged" {
            val r = ParameterBridge.prepareSqlForCalcite("SELECT id FROM customers", emptyList())
            r.sql shouldBe "SELECT id FROM customers"
            r.parameterOrder shouldBe emptyList()
        }

        "stray { without close brace passes through unchanged" {
            // Defensive: a literal "{" not followed by "}" must not be mis-parsed.
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT '{open' FROM customers",
                    emptyList(),
                )
            r.sql shouldBe "SELECT '{open' FROM customers"
        }

        // ---- typed = true (CAST(? AS T)) mode ----

        "typed mode emits CAST(? AS VARCHAR) for a string parameter" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE name = {n}",
                    listOf(SqlParam("n", "text", "Alice")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM customers WHERE name = CAST(? AS VARCHAR)"
        }

        "typed mode keeps the same ? count and order as bare mode (a CAST adds no extra ?)" {
            val sql = "SELECT id FROM orders WHERE customer_id = {cid} AND total > {min}"
            val params = listOf(SqlParam("cid", "int", 7), SqlParam("min", "float", 100.0))

            val bare = ParameterBridge.prepareSqlForCalcite(sql, params, typed = false)
            val typed = ParameterBridge.prepareSqlForCalcite(sql, params, typed = true)

            // Order is identical in both modes.
            typed.parameterOrder shouldBe bare.parameterOrder
            typed.parameterOrder shouldBe listOf("cid", "min")
            // Same number of placeholders — a CAST wraps a single ?, never adds one.
            typed.sql.count { it == '?' } shouldBe bare.sql.count { it == '?' }
        }

        "typed mode renders DECIMAL with explicit precision and scale (not bare DECIMAL)" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM orders WHERE rate = {r}",
                    listOf(SqlParam("r", "decimal", "1.5")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM orders WHERE rate = CAST(? AS DECIMAL(38, 10))"
        }

        "typed mode falls back to VARCHAR for an unknown surface type" {
            val r =
                ParameterBridge.prepareSqlForCalcite(
                    "SELECT id FROM customers WHERE blob = {b}",
                    listOf(SqlParam("b", "geography", "x")),
                    typed = true,
                )
            r.sql shouldBe "SELECT id FROM customers WHERE blob = CAST(? AS VARCHAR)"
        }
    })
