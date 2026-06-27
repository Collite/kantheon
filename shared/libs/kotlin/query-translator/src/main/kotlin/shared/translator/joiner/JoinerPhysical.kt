package shared.translator.joiner

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import shared.translator.framework.ModelForeignKey
import shared.translator.framework.ModelHandle
import shared.translator.wire.Expressions

/**
 * Phase 08 B5 — EXPAND_JOINS (physical) — Section E.
 *
 * Mirrors [JoinerLogical] but operates on `TableScan(DB, ...)` pairs using
 * [ModelHandle.foreignKeys] instead of ER relations.
 *
 * Runs only when `targetSchema = DB`. By that point the tree has been through MAP_TO_PHYSICAL,
 * so ER scans are already rewritten to TableScans. JoinerPhysical fills in conditions for any
 * remaining unconditioned joins between two DB tables.
 *
 * ## Don't-double-join
 *
 * Per master plan §138, JoinerPhysical must not re-insert a condition that JoinerLogical
 * already supplied (via an `er.relation` whose attribute pair maps to the same physical FK
 * after MAP_TO_PHYSICAL). The check is structural: a Join already carrying any condition is
 * left alone. This works because:
 *
 *   - JoinerLogical inserts a condition for the entity case.
 *   - MAP_TO_PHYSICAL rewrites both the surrounding Scans and the attribute references in
 *     overlying expressions; the join condition's ColumnRefs change from attribute names to
 *     column names but the condition stays attached.
 *   - JoinerPhysical sees a Join with a condition and skips.
 *
 * Idempotency: an already-conditioned Join is passed through unchanged.
 */
object JoinerPhysical {
    fun apply(
        plan: PlanNode,
        model: ModelHandle,
    ): JoinerResult {
        val warnings = mutableListOf<JoinerWarning>()
        val rewritten = walk(plan, model, warnings)
        return JoinerResult(plan = rewritten, warnings = warnings)
    }

    private fun walk(
        plan: PlanNode,
        model: ModelHandle,
        warnings: MutableList<JoinerWarning>,
    ): PlanNode {
        val withChildren = JoinerPlanWalker.rewriteChildren(plan) { walk(it, model, warnings) }
        if (withChildren.nodeCase != PlanNode.NodeCase.JOIN) return withChildren

        val join = withChildren.join
        // Don't-double-join.
        if (join.hasCondition()) return withChildren

        val leftTable = JoinerLogical.findFirstScanPublic(join.left, SchemaCode.DB)
        val rightTable = JoinerLogical.findFirstScanPublic(join.right, SchemaCode.DB)
        if (leftTable == null || rightTable == null) return withChildren

        val candidates = matchingForeignKeys(model.foreignKeys(), leftTable, rightTable)
        return when (candidates.size) {
            0 -> {
                warnings += JoinerWarning.NoRelation(leftTable, rightTable)
                withChildren
            }
            1 -> {
                val condition = buildEqualityCondition(candidates.single(), leftTable)
                withConditionSet(withChildren, condition)
            }
            else -> {
                // FK ambiguity is rare but possible (e.g. two FKs between the same two tables —
                // think `customer_id` + `billing_customer_id`). Same Cartesian fallback as
                // JoinerLogical's ambiguous case.
                warnings += JoinerWarning.AmbiguousRelations(leftTable, rightTable, emptyList())
                withChildren
            }
        }
    }

    /**
     * Return every FK whose `(from-table, to-table)` connects [a] and [b] (in either direction),
     * limited to v1.0's single-column FK shape.
     */
    private fun matchingForeignKeys(
        fks: List<ModelForeignKey>,
        a: QualifiedName,
        b: QualifiedName,
    ): List<ModelForeignKey> =
        fks.filter { fk ->
            if (fk.from.size != 1 || fk.to.size != 1) return@filter false
            val fromTable = fk.from.first().tableQname()
            val toTable = fk.to.first().tableQname()
            (fromTable == a && toTable == b) || (fromTable == b && toTable == a)
        }

    /**
     * v1 convention: column qnames are stored table-qualified as `<schema>.<namespace>.<table.column>`
     * — the `name` field contains `<table>.<column>`. Strip the column suffix to recover the
     * containing table's qname.
     */
    private fun QualifiedName.tableQname(): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(schemaCode)
            .setNamespace(namespace)
            .setName(name.substringBeforeLast('.'))
            .build()

    private fun buildEqualityCondition(
        fk: ModelForeignKey,
        leftTable: QualifiedName,
    ): Expression {
        val fromCol = fk.from.first()
        val toCol = fk.to.first()
        val fromColName = fromCol.name.substringAfterLast('.')
        val toColName = toCol.name.substringAfterLast('.')
        // Orient: which column is on the left vs right input depends on which side of the join
        // the FK's source table happens to be on.
        val (leftColName, rightColName) =
            if (fromCol.tableQname() == leftTable) {
                fromColName to toColName
            } else {
                toColName to fromColName
            }
        val leftRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(leftColName)
                        .setSourceAlias(Expressions.LEFT_INPUT_TAG),
                ).build()
        val rightRef =
            Expression
                .newBuilder()
                .setColumnRef(
                    ColumnRef
                        .newBuilder()
                        .setName(rightColName)
                        .setSourceAlias(Expressions.RIGHT_INPUT_TAG),
                ).build()
        return Expression
            .newBuilder()
            .setFunction(
                FunctionCall
                    .newBuilder()
                    .setOperation("eq")
                    .addOperands(leftRef)
                    .addOperands(rightRef),
            ).build()
    }

    private fun withConditionSet(
        plan: PlanNode,
        condition: Expression,
    ): PlanNode =
        plan
            .toBuilder()
            .setJoin(plan.join.toBuilder().setCondition(condition))
            .build()
}
