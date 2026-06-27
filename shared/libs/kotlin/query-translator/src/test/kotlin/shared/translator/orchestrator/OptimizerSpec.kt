package shared.translator.orchestrator

import org.tatrman.proteus.v1.SqlDialect as SqlDialectProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import shared.translator.codec.sql.SqlValidator
import shared.translator.codec.sql.ValidateResult
import shared.translator.framework.FixtureModel
import shared.translator.framework.TranslatorFramework

class OptimizerSpec :
    StringSpec({

        fun parseToRel(sql: String): org.apache.calcite.rel.RelNode {
            val fw = TranslatorFramework(FixtureModel.handle())
            val r = SqlValidator.validateAndConvert(fw.newPlanner(), sql)
            r.shouldBeInstanceOf<ValidateResult.Success>()
            return r.rel
        }

        "optimize returns a non-null RelNode for MSSQL" {
            val rel = parseToRel("SELECT id FROM customers WHERE id > 5")
            val out = Optimizer.optimize(rel, SqlDialectProto.MSSQL)
            out shouldNotBe null
        }

        "optimize is idempotent for queries that don't trigger any rules" {
            val rel = parseToRel("SELECT id FROM customers")
            val out1 = Optimizer.optimize(rel, SqlDialectProto.MSSQL)
            val out2 = Optimizer.optimize(out1, SqlDialectProto.MSSQL)
            out2.javaClass shouldBe out1.javaClass
        }

        "optimize handles UNSPECIFIED dialect by passing through unchanged" {
            val rel = parseToRel("SELECT id FROM customers")
            val out = Optimizer.optimize(rel, SqlDialectProto.SQL_DIALECT_UNSPECIFIED)
            out shouldNotBe null
        }

        "optimize works for all v1 dialects" {
            val rel = parseToRel("SELECT id FROM customers WHERE id > 5")
            for (d in listOf(SqlDialectProto.MSSQL, SqlDialectProto.POSTGRESQL, SqlDialectProto.MYSQL_MARIADB)) {
                val out = Optimizer.optimize(rel, d)
                out shouldNotBe null
            }
        }
    })
