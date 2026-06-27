package shared.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.tools.Planner
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework

class SqlValidatorSpec :
    StringSpec({

        fun parseAndValidate(sql: String): ValidateResult {
            val fw = TranslatorFramework(FixtureModel.handle())
            return SqlValidator.validateAndConvert(fw.newPlanner(), sql)
        }

        "validates a known-good SELECT against fixture schema" {
            val r = parseAndValidate("SELECT id, name FROM customers")
            r.shouldBeInstanceOf<ValidateResult.Success>()
        }

        "validates a JOIN against fixture schema" {
            val r =
                parseAndValidate(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            r.shouldBeInstanceOf<ValidateResult.Success>()
        }

        "validates aggregation" {
            val r = parseAndValidate("SELECT customer_id, COUNT(*) AS n FROM orders GROUP BY customer_id")
            r.shouldBeInstanceOf<ValidateResult.Success>()
        }

        "rejects unknown column with validation_failed code" {
            val r = parseAndValidate("SELECT does_not_exist FROM customers")
            r.shouldBeInstanceOf<ValidateResult.Failure>()
            val err = r.error
            err.code shouldBe "validation_failed"
        }

        "rejects unknown table with validation_failed code" {
            val r = parseAndValidate("SELECT id FROM no_such_table")
            r.shouldBeInstanceOf<ValidateResult.Failure>()
            val err = r.error
            err.code shouldBe "validation_failed"
            err.message.shouldContain("no_such_table")
        }

        // Calcite raises many conversion-stage failures as *unchecked* exceptions (the
        // "while converting <expr>" wrapper, charset-encode errors, CalciteContextException, …)
        // that the typed catches don't cover. Those must become a structured Failure — not escape
        // as a bare gRPC UNKNOWN — and the nested cause (where the real reason lives) must surface.
        // A mocked planner reproduces that shape deterministically.
        "maps unchecked Calcite errors to rel_conversion_failed and surfaces the nested cause" {
            val node = mockk<SqlNode>()
            val planner =
                mockk<Planner> {
                    every { parse(any<String>()) } returns node
                    every { validate(node) } returns node
                    every { rel(node) } throws
                        RuntimeException(
                            "while converting `u`.`name` = 'Poštovné'",
                            IllegalStateException("Failed to encode 'Poštovné' in character set 'ISO-8859-1'"),
                        )
                }

            val r = SqlValidator.validateAndConvert(planner, "SELECT name FROM customers")

            r.shouldBeInstanceOf<ValidateResult.Failure>()
            r.error.code shouldBe "rel_conversion_failed"
            // Both the outer wrapper AND the root cause are present — the whole point of the fix.
            r.error.message.shouldContain("while converting")
            r.error.message.shouldContain("Failed to encode")
            r.error.message.shouldContain("ISO-8859-1")
        }

        "collapses duplicate messages when a wrapper copies its cause's text" {
            val node = mockk<SqlNode>()
            val planner =
                mockk<Planner> {
                    every { parse(any<String>()) } returns node
                    every { validate(node) } returns node
                    every { rel(node) } throws RuntimeException("boom", RuntimeException("boom"))
                }

            val r = SqlValidator.validateAndConvert(planner, "SELECT 1")

            r.shouldBeInstanceOf<ValidateResult.Failure>()
            r.error.message shouldBe "boom"
        }
    })
