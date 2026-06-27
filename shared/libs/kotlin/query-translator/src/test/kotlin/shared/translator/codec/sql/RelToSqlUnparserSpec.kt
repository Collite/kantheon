package shared.translator.codec.sql

import org.tatrman.proteus.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework

class RelToSqlUnparserSpec :
    StringSpec({

        fun parseToRel(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "round-trips a simple SELECT in MSSQL dialect" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
            out.shouldContainIgnoringCase("select")
            out.shouldContainIgnoringCase("customers")
            // MSSQL dialect uses square-bracket identifiers, not backticks.
            out.shouldNotContain("`")
        }

        "round-trips a JOIN in MSSQL dialect" {
            val rel =
                parseToRel(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
            out.shouldContainIgnoringCase("join")
            out.shouldContainIgnoringCase("customers")
            out.shouldContainIgnoringCase("orders")
        }

        "round-trips a GROUP BY + COUNT in MSSQL dialect" {
            val rel =
                parseToRel(
                    "SELECT customer_id, COUNT(*) AS n FROM orders GROUP BY customer_id",
                )
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
            out.shouldContainIgnoringCase("group by")
            out.shouldContainIgnoringCase("count")
        }

        "POSTGRES dialect emits double-quoted identifiers" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.POSTGRESQL)
            out.shouldContainIgnoringCase("select")
            out.shouldNotContain("[")
        }

        "MYSQL dialect emits backtick identifiers" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MYSQL_MARIADB)
            out.shouldContainIgnoringCase("select")
        }

        // Phase 08 A3 / DF-T04 — DuckDB dialect (Postgres-compatible for our constructs).
        "DUCKDB dialect emits a SELECT (Postgres-shaped, no MSSQL brackets / no MySQL backticks)" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.DUCKDB)
            out.shouldContainIgnoringCase("select")
            out.shouldContainIgnoringCase("customers")
            out.shouldNotContain("[")
            out.shouldNotContain("`")
        }

        "DUCKDB dialect round-trips a JOIN" {
            val rel =
                parseToRel(
                    "SELECT c.name, o.total FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.DUCKDB)
            out.shouldContainIgnoringCase("join")
            out.shouldContainIgnoringCase("customers")
            out.shouldContainIgnoringCase("orders")
        }

        // Issue #57 Phase A — strip the leading `db` virtual schema-code prefix from emitted
        // SQL. The Calcite catalog resolves DB tables via `root.db.<namespace>.<table>`, but
        // `db` is a Calcite-internal token — never a real database name. Engines would
        // interpret 3-part references as `<database>.<schema>.<table>` and fail.
        "MSSQL dialect strips the [db] virtual schema-code prefix" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
            out.shouldNotContain("[db].")
            // The bare table reference (with its real schema) survives.
            out.shouldContainIgnoringCase("[dbo].[customers]")
        }

        "POSTGRES dialect strips the \"db\" virtual schema-code prefix" {
            val rel = parseToRel("SELECT id, name FROM customers")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.POSTGRESQL)
            out.shouldNotContain("\"db\".")
            out.shouldContainIgnoringCase("\"dbo\".\"customers\"")
        }

        // The strip is a SqlNode-tree pass over identifiers — string literals
        // containing the text `[db].` must NOT be rewritten.
        "string literals containing [db]. survive the identifier strip" {
            val rel = parseToRel("SELECT id FROM customers WHERE name = '[db].keepme'")
            val out = RelToSqlUnparser.unparse(rel, SqlDialectProto.MSSQL)
            out.shouldContainIgnoringCase("'[db].keepme'")
            out.shouldContainIgnoringCase("[dbo].[customers]")
        }
    })
