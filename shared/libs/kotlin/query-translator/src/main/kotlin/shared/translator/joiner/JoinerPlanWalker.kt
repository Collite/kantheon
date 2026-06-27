package shared.translator.joiner

import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PlanNode

/**
 * Generic post-order child-rewrite helper for wire-form [PlanNode] trees.
 *
 * `rewriteChildren(node) { walk(it) }` replaces each child of `node` with the result of `walk`
 * and returns either the same node (if no child changed — reference equality) or a new node
 * with the rewritten children. Leaves (`TABLE_SCAN`, `SCAN`, `VALUES`, `WORKSPACE_REF`,
 * unknown) are returned unchanged.
 *
 * Shared by [JoinerLogical], [JoinerPhysical], [shared.translator.schema.MapToPhysical], and
 * [shared.translator.schema.Unfold]. Living in `joiner/` (and reused from `schema/`) avoids a
 * second helper file; the package boundary is a hint, not a contract.
 */
object JoinerPlanWalker {
    fun rewriteChildren(
        plan: PlanNode,
        rewrite: (PlanNode) -> PlanNode,
    ): PlanNode =
        when (plan.nodeCase) {
            PlanNode.NodeCase.PROJECT -> {
                val newInput = rewrite(plan.project.input)
                val newExprs =
                    plan.project.expressionsList.map { ne ->
                        val newExpr = rewriteExprSubqueries(ne.expression, rewrite)
                        if (newExpr === ne.expression) ne else ne.toBuilder().setExpression(newExpr).build()
                    }
                val exprsChanged = newExprs.zip(plan.project.expressionsList).any { (a, b) -> a !== b }
                if (newInput === plan.project.input && !exprsChanged) {
                    plan
                } else {
                    plan
                        .toBuilder()
                        .setProject(
                            plan.project
                                .toBuilder()
                                .setInput(newInput)
                                .clearExpressions()
                                .addAllExpressions(newExprs),
                        ).build()
                }
            }
            PlanNode.NodeCase.FILTER -> {
                val newInput = rewrite(plan.filter.input)
                val newCond = rewriteExprSubqueries(plan.filter.condition, rewrite)
                if (newInput === plan.filter.input && newCond === plan.filter.condition) {
                    plan
                } else {
                    plan
                        .toBuilder()
                        .setFilter(
                            plan.filter
                                .toBuilder()
                                .setInput(newInput)
                                .setCondition(newCond),
                        ).build()
                }
            }
            PlanNode.NodeCase.JOIN -> {
                val newLeft = rewrite(plan.join.left)
                val newRight = rewrite(plan.join.right)
                val newCond =
                    if (plan.join.hasCondition()) rewriteExprSubqueries(plan.join.condition, rewrite) else null
                val condChanged = newCond != null && newCond !== plan.join.condition
                if (newLeft === plan.join.left && newRight === plan.join.right && !condChanged) {
                    plan
                } else {
                    val joinB =
                        plan.join
                            .toBuilder()
                            .setLeft(newLeft)
                            .setRight(newRight)
                    if (newCond != null) joinB.setCondition(newCond)
                    plan.toBuilder().setJoin(joinB).build()
                }
            }
            PlanNode.NodeCase.AGGREGATE -> {
                val newInput = rewrite(plan.aggregate.input)
                if (newInput === plan.aggregate.input) {
                    plan
                } else {
                    plan.toBuilder().setAggregate(plan.aggregate.toBuilder().setInput(newInput)).build()
                }
            }
            PlanNode.NodeCase.SORT -> {
                val newInput = rewrite(plan.sort.input)
                if (newInput === plan.sort.input) {
                    plan
                } else {
                    plan.toBuilder().setSort(plan.sort.toBuilder().setInput(newInput)).build()
                }
            }
            PlanNode.NodeCase.LIMIT_OFFSET -> {
                val newInput = rewrite(plan.limitOffset.input)
                if (newInput === plan.limitOffset.input) {
                    plan
                } else {
                    plan.toBuilder().setLimitOffset(plan.limitOffset.toBuilder().setInput(newInput)).build()
                }
            }
            PlanNode.NodeCase.SUBQUERY -> {
                val newInner = rewrite(plan.subquery.subquery)
                if (newInner === plan.subquery.subquery) {
                    plan
                } else {
                    plan.toBuilder().setSubquery(plan.subquery.toBuilder().setSubquery(newInner)).build()
                }
            }
            else -> plan
        }

    /**
     * Apply [rewrite] to every [PlanNode] embedded as an expression-level subquery (see
     * `SubqueryExpression`) inside [expr], recursing through `FunctionCall` operands and `Cast`
     * values so a subquery nested anywhere in the expression tree is reached. Returns [expr]
     * unchanged (reference-equal) when nothing nested changed — preserving the post-order
     * walker's no-op contract so a stage that touches nothing returns the same tree.
     */
    fun rewriteExprSubqueries(
        expr: Expression,
        rewrite: (PlanNode) -> PlanNode,
    ): Expression =
        when (expr.exprCase) {
            Expression.ExprCase.SUBQUERY -> {
                val inner = expr.subquery
                val newPlan = rewrite(inner.subquery)
                val newOperands = inner.operandsList.map { rewriteExprSubqueries(it, rewrite) }
                val operandsChanged = newOperands.zip(inner.operandsList).any { (a, b) -> a !== b }
                if (newPlan === inner.subquery && !operandsChanged) {
                    expr
                } else {
                    expr
                        .toBuilder()
                        .setSubquery(
                            inner
                                .toBuilder()
                                .setSubquery(newPlan)
                                .clearOperands()
                                .addAllOperands(newOperands),
                        ).build()
                }
            }
            Expression.ExprCase.FUNCTION -> {
                val fn = expr.function
                val newOperands = fn.operandsList.map { rewriteExprSubqueries(it, rewrite) }
                val changed = newOperands.zip(fn.operandsList).any { (a, b) -> a !== b }
                if (!changed) {
                    expr
                } else {
                    expr
                        .toBuilder()
                        .setFunction(fn.toBuilder().clearOperands().addAllOperands(newOperands))
                        .build()
                }
            }
            Expression.ExprCase.CAST -> {
                val newValue = rewriteExprSubqueries(expr.cast.value, rewrite)
                if (newValue === expr.cast.value) {
                    expr
                } else {
                    expr.toBuilder().setCast(expr.cast.toBuilder().setValue(newValue)).build()
                }
            }
            else -> expr
        }
}
