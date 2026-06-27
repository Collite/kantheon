package shared.translator.orchestrator

import org.tatrman.proteus.v1.SqlDialect as SqlDialectProto
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.rules.CoreRules
import org.slf4j.LoggerFactory

/**
 * HEP planner pass for v1.
 *
 * Calcite engagement rule #5: the planner is for custom rules and dialect-
 * specific transformations, not for classical optimisation. v1 keeps the rule
 * set small and per-dialect; the bulk of optimisation is delegated to the
 * target engine (MS SQL Server, etc.).
 *
 * A fresh [HepPlanner] is built per call (no cross-call rule-set sharing) —
 * Calcite's planner state is mutated during planning, so reuse is unsafe.
 *
 * v1 rule set for MSSQL: keep small. Start with
 * [CoreRules.FILTER_REDUCE_EXPRESSIONS] which folds tautological/contradictory
 * predicates into simple booleans. Add more rules in v1.5+ as needs surface.
 */
object Optimizer {
    private val log = LoggerFactory.getLogger(Optimizer::class.java)

    fun optimize(
        rel: RelNode,
        dialectCode: SqlDialectProto,
    ): RelNode {
        val program =
            HepProgramBuilder()
                .also { addRulesFor(dialectCode, it) }
                .build()
        val planner = HepPlanner(program)
        planner.root = rel
        val out = planner.findBestExp()
        if (log.isDebugEnabled) {
            log.debug(
                "Optimizer ran for dialect={} ({} -> {})",
                dialectCode,
                rel.javaClass.simpleName,
                out.javaClass.simpleName,
            )
        }
        return out
    }

    private fun addRulesFor(
        dialectCode: SqlDialectProto,
        b: HepProgramBuilder,
    ) {
        when (dialectCode) {
            SqlDialectProto.MSSQL -> {
                b.addRuleInstance(CoreRules.FILTER_REDUCE_EXPRESSIONS)
            }
            SqlDialectProto.POSTGRESQL,
            SqlDialectProto.MYSQL_MARIADB,
            SqlDialectProto.DUCKDB,
            -> {
                // Phase 08 A3 — DuckDB extends Postgres, so the same rule set applies.
                b.addRuleInstance(CoreRules.FILTER_REDUCE_EXPRESSIONS)
            }
            SqlDialectProto.SQL_DIALECT_UNSPECIFIED,
            SqlDialectProto.UNRECOGNIZED,
            -> {
                // No rules — caller must specify a dialect to get optimisation.
            }
        }
    }
}
