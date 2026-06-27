package shared.translator.wire

import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.Row
import org.tatrman.plan.v1.SortKey
import org.tatrman.plan.v1.SortNode
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.plan.v1.parseSchemaCode
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.Sort
import org.apache.calcite.rel.core.TableScan
import org.apache.calcite.rel.core.Values
import org.apache.calcite.rex.RexLiteral

/**
 * Encode a Calcite [RelNode] into the v1 proto [PlanNode] wire format.
 *
 * Covers the full v1 RelOp subset: TableScan, Project, Filter, Join,
 * Aggregate, Sort, LimitOffset, Values. SubqueryNode is reached transparently
 * via input recursion when the Translator wraps a subplan.
 *
 * Anything outside the v1 subset throws [UnsupportedOperationException] with
 * the offending RelNode class named — the wire format must stay clean.
 */
object PlanNodeEncoder {
/**
     * Encode the rel without parameter-name restoration. `RexDynamicParam`s emit their positional
     * shape (`?N`); this is the call shape Calcite-only callers (no orchestrator yet) use.
     */
    fun encode(rel: RelNode): PlanNode = encode(rel, parameterNames = emptyMap())

    /**
     * Phase 08 A2 — encode with the original parameter names restored on `RexDynamicParam` nodes.
     * The orchestrator (Section J) supplies `parameterNames` from
     * [shared.translator.params.PreparedSql.parameterOrder] (`index -> name`). Empty map → same
     * behaviour as the single-arg overload.
     *
     * The three-level SchemaPlus tree ensures Calcite always provides a 3-part qualified name
     * `<schema>.<namespace>.<name>`, so no namespace-recovery workaround is needed.
     */
    fun encode(
        rel: RelNode,
        parameterNames: Map<Int, String> = emptyMap(),
    ): PlanNode =
        when (rel) {
            is TableScan -> encodeTableScan(rel)
            is Project -> encodeProject(rel, parameterNames)
            is Filter -> encodeFilter(rel, parameterNames)
            is Join -> encodeJoin(rel, parameterNames)
            is Aggregate -> encodeAggregate(rel, parameterNames)
            is Sort -> encodeSort(rel, parameterNames)
            is Values -> encodeValues(rel)
            else -> throw UnsupportedOperationException(
                "RelOp '${rel.javaClass.simpleName}' is not in the v1 wire format",
            )
        }

    private fun encodeTableScan(rel: TableScan): PlanNode {
        val path = rel.table!!.qualifiedName
        require(path.size == 3) {
            "Expected 3-part qualified name <schema>.<namespace>.<name>, got: $path"
        }

        val schemaCode =
            parseSchemaCode(path[0])
                ?: error("Unknown schema code '${path[0]}' in qualified name $path")
        val namespace = path[1]
        val tableName = path[2]
        val qname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(schemaCode)
                .setNamespace(namespace)
                .setName(tableName)
                .build()

        val outputCols =
            rel.rowType.fieldList.map { f ->
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(f.name)
                    .setType(Expressions.surfaceTypeOf(f.type))
                    .build()
            }

        val builder = PlanNode.newBuilder()
        if (schemaCode == org.tatrman.plan.v1.SchemaCode.DB) {
            builder.setTableScan(TableScanNode.newBuilder().setTable(qname).addAllOutputColumns(outputCols))
        } else {
            builder.setScan(ScanNode.newBuilder().setObject(qname).addAllOutputColumns(outputCols))
        }
        return builder.build()
    }

    private fun encodeProject(
        rel: Project,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val builder = ProjectNode.newBuilder().setInput(encode(rel.input, parameterNames))
        val fieldNames =
            rel.input.rowType.fieldList
                .map { it.name }
        val ctx =
            Expressions.ResolveContext(
                fieldNames = fieldNames,
                parameterNames = parameterNames,
            )
        rel.projects.forEachIndexed { idx, expr ->
            builder.addExpressions(
                NamedExpression
                    .newBuilder()
                    .setExpression(Expressions.encode(expr, ctx))
                    .setAlias(rel.rowType.fieldList[idx].name),
            )
        }
        return PlanNode.newBuilder().setProject(builder).build()
    }

    private fun encodeFilter(
        rel: Filter,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val fieldNames =
            rel.input.rowType.fieldList
                .map { it.name }
        val ctx =
            Expressions.ResolveContext(
                fieldNames = fieldNames,
                parameterNames = parameterNames,
            )
        return PlanNode
            .newBuilder()
            .setFilter(
                FilterNode
                    .newBuilder()
                    .setInput(encode(rel.input, parameterNames))
                    .setCondition(Expressions.encode(rel.condition, ctx)),
            ).build()
    }

    private fun encodeJoin(
        rel: Join,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val joinType =
            when (rel.joinType) {
                JoinRelType.INNER -> JoinType.INNER
                JoinRelType.LEFT -> JoinType.LEFT
                JoinRelType.RIGHT -> JoinType.RIGHT
                JoinRelType.FULL -> JoinType.FULL
                else -> throw UnsupportedOperationException(
                    "Join type '${rel.joinType}' is not in the v1 wire format",
                )
            }
        val builder =
            JoinNode
                .newBuilder()
                .setLeft(encode(rel.left, parameterNames))
                .setRight(encode(rel.right, parameterNames))
                .setJoinType(joinType)
        if (!rel.condition.isAlwaysTrue) {
            // Phase 08 A1 — RESOLVE. Condition is over the combined row type; encode RexInputRefs
            // with the true field name plus a `$L`/`$R` source-alias hint so the decoder can
            // route the lookup into the correct join input (RelBuilder.field(2, ord, name)).
            val combined = rel.rowType.fieldList.map { it.name }
            val ctx =
                Expressions.ResolveContext(
                    fieldNames = combined,
                    joinSplit = rel.left.rowType.fieldCount,
                    parameterNames = parameterNames,
                    // Original per-input names so a `$R` ref to a name Calcite
                    // uniquified in `combined` (e.g. IDSTRED→IDSTRED0) still decodes
                    // against the right input, where it's the un-suffixed name.
                    leftFieldNames =
                        rel.left.rowType.fieldList
                            .map { it.name },
                    rightFieldNames =
                        rel.right.rowType.fieldList
                            .map { it.name },
                )
            builder.setCondition(Expressions.encode(rel.condition, ctx))
        }
        return PlanNode.newBuilder().setJoin(builder).build()
    }

    private fun encodeAggregate(
        rel: Aggregate,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val builder = AggregateNode.newBuilder().setInput(encode(rel.input, parameterNames))
        // Group keys are represented as positional column refs into the input.
        rel.groupSet.forEach { idx ->
            val field = rel.input.rowType.fieldList[idx]
            builder.addGroupKeys(
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(field.name)
                    .setType(Expressions.surfaceTypeOf(field.type)),
            )
        }
        rel.aggCallList.forEachIndexed { idx, call ->
            val outName = rel.rowType.fieldList[rel.groupSet.cardinality() + idx].name
            val agg =
                org.tatrman.plan.v1.AggregateCall
                    .newBuilder()
                    .setFunction(call.aggregation.name.lowercase())
                    .setDistinct(call.isDistinct)
                    .setAlias(outName)
            call.argList.forEach { argIdx ->
                val argField = rel.input.rowType.fieldList[argIdx]
                agg.addArgs(
                    org.tatrman.plan.v1.ColumnRef
                        .newBuilder()
                        .setName(argField.name)
                        .setType(Expressions.surfaceTypeOf(argField.type)),
                )
            }
            builder.addAggregates(agg)
        }
        return PlanNode.newBuilder().setAggregate(builder).build()
    }

    private fun encodeSort(
        rel: Sort,
        parameterNames: Map<Int, String>,
    ): PlanNode {
        val limit = rel.fetch?.let { (it as? RexLiteral)?.value2 as? Number }?.toLong()
        val offset = rel.offset?.let { (it as? RexLiteral)?.value2 as? Number }?.toLong()

        if (rel.collation.fieldCollations.isEmpty() && (limit != null || offset != null)) {
            val lo = LimitOffsetNode.newBuilder().setInput(encode(rel.input, parameterNames))
            limit?.let { lo.setLimit(it) }
            offset?.let { lo.setOffset(it) }
            return PlanNode.newBuilder().setLimitOffset(lo).build()
        }

        val sortBuilder = SortNode.newBuilder().setInput(encode(rel.input, parameterNames))
        rel.collation.fieldCollations.forEach { fc ->
            val field = rel.input.rowType.fieldList[fc.fieldIndex]
            sortBuilder.addSortKeys(
                SortKey
                    .newBuilder()
                    .setColumn(
                        org.tatrman.plan.v1.ColumnRef
                            .newBuilder()
                            .setName(field.name)
                            .setType(Expressions.surfaceTypeOf(field.type)),
                    ).setDescending(fc.direction.isDescending)
                    .setNullsFirst(fc.nullDirection == org.apache.calcite.rel.RelFieldCollation.NullDirection.FIRST),
            )
        }
        val sortNode = PlanNode.newBuilder().setSort(sortBuilder).build()

        if (limit != null || offset != null) {
            val lo = LimitOffsetNode.newBuilder().setInput(sortNode)
            limit?.let { lo.setLimit(it) }
            offset?.let { lo.setOffset(it) }
            return PlanNode.newBuilder().setLimitOffset(lo).build()
        }
        return sortNode
    }

    private fun encodeValues(rel: Values): PlanNode {
        val builder = ValuesNode.newBuilder()
        rel.tuples.forEach { tuple ->
            val rowBuilder = Row.newBuilder()
            tuple.forEach { rex ->
                val expr = Expressions.encode(rex)
                rowBuilder.addCells(expr.literal)
            }
            builder.addRows(rowBuilder)
        }
        rel.rowType.fieldList.forEach { field ->
            builder.addOutputColumns(
                org.tatrman.plan.v1.ColumnRef
                    .newBuilder()
                    .setName(field.name)
                    .setType(Expressions.surfaceTypeOf(field.type)),
            )
        }
        return PlanNode.newBuilder().setValues(builder).build()
    }
}
