package shared.translator.wire

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.SubqueryExpression
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexSubQuery
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlOperator
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.tools.RelBuilder

/**
 * RexNode ↔ Expression encoders / decoders for the v1 wire format.
 *
 * Conversion is opinionated: only the operators in the v1 RelOp subset's
 * standardised expression enum (see v1-architecture.md §3.2) are handled.
 * Anything else throws [UnsupportedOperationException] with the offending
 * operator named. The Translator's TO_AST stage is responsible for catching
 * those cases earlier; this encoder is the last-line check that the wire
 * format stays clean.
 *
 * Phase 08 A1 / DF-T01 — RESOLVE stage. `RexInputRef`s are encoded as named
 * `ColumnRef`s (instead of positional `$idx`) so the wire shape survives
 * round-trips, including across joins. For join conditions the encoder also
 * sets `source_alias = "$L"`/`"$R"` so the decoder can route the lookup
 * into the correct join input via `RelBuilder.field(inputCount, ord, name)`.
 * Bare positional `$idx` names remain accepted on decode for backward
 * compatibility with plans produced before RESOLVE landed.
 */
object Expressions {
    /** Synthetic source-alias markers used to route a [ColumnRef] into a join input on decode. */
    const val LEFT_INPUT_TAG: String = "\$L"
    const val RIGHT_INPUT_TAG: String = "\$R"

    /**
     * Resolution context threaded through [encode]. `fieldNames` is the field-name list of the
     * RelDataType the expression is interpreted against (the input row type for Filter / Project,
     * the combined row type for Join.condition). `joinSplit` is non-null only inside a Join
     * condition; it carries the field-count of the left input so the encoder can decide whether
     * a `RexInputRef.index` belongs to the left (`< joinSplit`) or right (`>= joinSplit`) input.
     *
     * Phase 08 A2 — `parameterNames` is an optional positional-index → original parameter name
     * map (built by [shared.translator.params.ParameterBridge] and carried by the Translator
     * orchestrator). When supplied, the encoder restores the original `{name}` on
     * `RexDynamicParam`s; otherwise the legacy `?N` positional shape is emitted.
     */
    data class ResolveContext(
        val fieldNames: List<String>,
        val joinSplit: Int? = null,
        val parameterNames: Map<Int, String> = emptyMap(),
        // Per-input field names for a Join condition. Calcite uniquifies duplicate
        // names in the COMBINED join row type (a right-side `IDSTRED` colliding with
        // a left-side `IDSTRED` becomes `IDSTRED0`), but on decode a `$L`/`$R` ref is
        // resolved against the individual input — where the name is still the
        // original. So join-condition refs must be encoded with the per-input name,
        // not the combined one. Empty → fall back to `fieldNames` (no per-input info).
        val leftFieldNames: List<String> = emptyList(),
        val rightFieldNames: List<String> = emptyList(),
    ) {
        companion object {
            /** Marker for places that haven't been migrated yet — falls back to positional encoding. */
            val NONE: ResolveContext = ResolveContext(emptyList(), null)
        }
    }

    fun encode(
        rex: RexNode,
        ctx: ResolveContext = ResolveContext.NONE,
    ): Expression =
        when (rex) {
            is RexLiteral ->
                Expression
                    .newBuilder()
                    .setLiteral(encodeLiteral(rex))
                    .setResultType(surfaceTypeOf(rex.type))
                    .build()
            is RexInputRef -> encodeInputRef(rex, ctx)
            // `RexSubQuery` extends `RexCall`, so it MUST be matched before the generic
            // `is RexCall` branch — otherwise its inner subquery RelNode (held in `.rel`,
            // not in `.operands`) is silently dropped and the operator name leaks out as a
            // bare `$scalar_query` function the decoder can't reconstruct.
            is RexSubQuery -> encodeSubquery(rex, ctx)
            is RexCall ->
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation(operationCode(rex))
                            .addAllOperands(rex.operands.map { encode(it, ctx) }),
                    ).setResultType(surfaceTypeOf(rex.type))
                    .build()
            is RexDynamicParam -> {
                // Phase 08 A2 — name restoration. When the orchestrator threaded the prepared
                // `parameterNames` map in, emit the original `{name}` here; otherwise fall back
                // to the positional `?N` shape so callers without the map stay back-compat.
                val restoredName = ctx.parameterNames[rex.index] ?: "?${rex.index}"
                Expression
                    .newBuilder()
                    .setParameter(
                        ParameterRef
                            .newBuilder()
                            .setName(restoredName)
                            .setPositionalIndex(rex.index),
                    ).setResultType(surfaceTypeOf(rex.type))
                    .build()
            }
            else -> throw UnsupportedOperationException(
                "RexNode kind '${rex.javaClass.simpleName}' is not in the v1 wire format",
            )
        }

    private fun encodeInputRef(
        rex: RexInputRef,
        ctx: ResolveContext,
    ): Expression {
        val builder =
            ColumnRef
                .newBuilder()
                .setType(surfaceTypeOf(rex.type))
        // No resolution context (legacy callers, or a hop where we don't know the row type) →
        // fall back to the v1.0 positional shape so behaviour is unchanged.
        if (ctx.fieldNames.isEmpty() || rex.index !in ctx.fieldNames.indices) {
            builder.setName("\$${rex.index}")
        } else {
            val split = ctx.joinSplit
            if (split != null) {
                val isLeft = rex.index < split
                builder.setSourceAlias(if (isLeft) LEFT_INPUT_TAG else RIGHT_INPUT_TAG)
                // Emit the name as it appears in the TARGET INPUT, not the combined
                // (uniquified) join row type — the decoder resolves `$L`/`$R` against
                // the input. Falls back to the combined name when per-input names
                // aren't supplied (preserves the prior positional/combined behaviour).
                val perInput =
                    if (isLeft) {
                        ctx.leftFieldNames.getOrNull(rex.index)
                    } else {
                        ctx.rightFieldNames.getOrNull(rex.index - split)
                    }
                builder.setName(perInput ?: ctx.fieldNames[rex.index])
            } else {
                builder.setName(ctx.fieldNames[rex.index])
            }
        }
        return Expression
            .newBuilder()
            .setColumnRef(builder)
            .setResultType(surfaceTypeOf(rex.type))
            .build()
    }

    /**
     * Encode a Calcite [RexSubQuery] (an expression-level scalar / EXISTS / IN subquery) into a
     * [SubqueryExpression]. The nested subquery RelNode rides in `RexSubQuery.rel`; we recurse
     * into [PlanNodeEncoder] for it. IN-subquery left-hand-side expressions live in
     * `RexSubQuery.operands` (empty for scalar / EXISTS) and are encoded inline.
     *
     * Correlated subqueries (whose `rel` references the outer row via a `RexCorrelVariable`) are
     * out of the v1 subset — they surface as an [UnsupportedOperationException] from the nested
     * [PlanNodeEncoder.encode] when it meets the correlation reference, not silently.
     */
    private fun encodeSubquery(
        rex: RexSubQuery,
        ctx: ResolveContext,
    ): Expression {
        val kind =
            when (rex.kind) {
                SqlKind.SCALAR_QUERY -> "scalar"
                SqlKind.EXISTS -> "exists"
                SqlKind.IN -> "in"
                else -> throw UnsupportedOperationException(
                    "Subquery kind '${rex.kind}' is not in the v1 wire format",
                )
            }
        val sub =
            SubqueryExpression
                .newBuilder()
                .setSubquery(PlanNodeEncoder.encode(rex.rel, ctx.parameterNames))
                .setKind(kind)
                .addAllOperands(rex.operands.map { encode(it, ctx) })
        return Expression
            .newBuilder()
            .setSubquery(sub)
            .setResultType(surfaceTypeOf(rex.type))
            .build()
    }

    private fun encodeLiteral(lit: RexLiteral): Literal {
        val builder = Literal.newBuilder().setType(surfaceTypeOf(lit.type))
        if (lit.value == null) {
            return builder.setIsNull(true).build()
        }
        return when (lit.type.sqlTypeName) {
            SqlTypeName.VARCHAR, SqlTypeName.CHAR ->
                builder.setStringValue(lit.value2.toString()).build()
            SqlTypeName.BOOLEAN ->
                builder.setBoolValue(lit.value2 as Boolean).build()
            SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.SMALLINT, SqlTypeName.TINYINT ->
                builder.setIntValue((lit.value2 as Number).toLong()).build()
            SqlTypeName.DECIMAL, SqlTypeName.DOUBLE, SqlTypeName.FLOAT, SqlTypeName.REAL ->
                builder.setFloatValue((lit.value2 as Number).toDouble()).build()
            SqlTypeName.DATE, SqlTypeName.TIME, SqlTypeName.TIMESTAMP ->
                builder.setDatetimeValue(lit.value2.toString()).build()
            else -> throw UnsupportedOperationException(
                "Literal of SqlTypeName '${lit.type.sqlTypeName}' is not in the v1 wire format",
            )
        }
    }

    private fun operationCode(call: RexCall): String =
        when (call.kind) {
            SqlKind.AND -> "and"
            SqlKind.OR -> "or"
            SqlKind.NOT -> "not"
            SqlKind.EQUALS -> "eq"
            SqlKind.NOT_EQUALS -> "ne"
            SqlKind.LESS_THAN -> "lt"
            SqlKind.LESS_THAN_OR_EQUAL -> "le"
            SqlKind.GREATER_THAN -> "gt"
            SqlKind.GREATER_THAN_OR_EQUAL -> "ge"
            SqlKind.PLUS -> "add"
            SqlKind.MINUS -> "sub"
            SqlKind.TIMES -> "mul"
            SqlKind.DIVIDE -> "div"
            SqlKind.IS_NULL -> "is_null"
            SqlKind.IS_NOT_NULL -> "is_not_null"
            // Phase 08 B4 / DF-S05 + DF-DSL04 — first-class set/pattern membership.
            SqlKind.IN -> "in"
            SqlKind.LIKE -> "like"
            else -> call.operator.name.lowercase()
        }

    /**
     * Decode an [Expression] back into a [RexNode] using the supplied
     * [RelBuilder]. Inverse of [encode].
     *
     * Column references are resolved against the builder's current peek's
     * row type (so `decode` MUST be called after the input has been pushed
     * onto the builder's stack).
     */
    fun decode(
        builder: RelBuilder,
        expr: Expression,
    ): RexNode =
        when (expr.exprCase) {
            Expression.ExprCase.LITERAL -> decodeLiteral(builder, expr.literal)
            Expression.ExprCase.COLUMN_REF -> decodeColumnRef(builder, expr.columnRef)
            Expression.ExprCase.FUNCTION ->
                // CAST is encoded as a FunctionCall (operation="cast") whose target type
                // rides on the *Expression's* result_type, not on the call — so it can't
                // go through the generic operator path (which has no type to cast to).
                if (expr.function.operation.equals("cast", ignoreCase = true)) {
                    decodeCast(builder, expr.function, expr.resultType)
                } else {
                    decodeFunctionCall(builder, expr.function)
                }
            Expression.ExprCase.PARAMETER ->
                decodeParameter(builder, expr.parameter, expr.resultType)
            Expression.ExprCase.SUBQUERY ->
                decodeSubquery(builder, expr.subquery)
            Expression.ExprCase.CAST ->
                throw UnsupportedOperationException(
                    "CastExpression decoding is TODO; v1 codecs preserve casts via Expression.cast",
                )
            else -> throw UnsupportedOperationException(
                "Expression case '${expr.exprCase}' is not in the v1 wire format",
            )
        }

    private fun decodeLiteral(
        builder: RelBuilder,
        lit: Literal,
    ): RexNode {
        if (lit.isNull) return builder.literal(null)
        return when (lit.valueCase) {
            Literal.ValueCase.STRING_VALUE -> builder.literal(lit.stringValue)
            Literal.ValueCase.INT_VALUE -> builder.literal(lit.intValue)
            Literal.ValueCase.FLOAT_VALUE -> builder.literal(lit.floatValue)
            Literal.ValueCase.BOOL_VALUE -> builder.literal(lit.boolValue)
            Literal.ValueCase.DATETIME_VALUE -> builder.literal(lit.datetimeValue)
            else -> builder.literal(null)
        }
    }

    private fun decodeColumnRef(
        builder: RelBuilder,
        ref: ColumnRef,
    ): RexNode {
        val name = ref.name
        // Phase 08 A1 — RESOLVE: a source_alias hint of `$L` / `$R` routes the lookup into the
        // correct join input. RelBuilder's three-arg `field(inputCount, ord, name)` consults the
        // specified input even when both join inputs are on the builder stack — without the hint
        // the bare `field(name)` only sees the top-of-stack rel and a join's right-side reference
        // mis-resolves into the right input by index, going out of range.
        when (ref.sourceAlias) {
            LEFT_INPUT_TAG -> return builder.field(2, 0, name)
            RIGHT_INPUT_TAG -> return builder.field(2, 1, name)
        }
        if (name.startsWith("\$")) {
            val idx =
                name.drop(1).toIntOrNull()
                    ?: throw IllegalArgumentException("Malformed positional column ref '$name'")
            return builder.field(idx)
        }
        return builder.field(name)
    }

    private fun decodeFunctionCall(
        builder: RelBuilder,
        fn: FunctionCall,
    ): RexNode {
        val operator = operatorFor(fn.operation)
        val operands = fn.operandsList.map { decode(builder, it) }
        return builder.call(operator, operands)
    }

    /**
     * Decode a CAST (encoded as `FunctionCall(operation="cast")` with the single value operand;
     * the target type is the wrapping Expression's surface [resultType]). Rebuilt as a Calcite
     * `RexCall(CAST)` via [org.apache.calcite.rex.RexBuilder.makeCast] so RelToSql renders the
     * dialect-appropriate `CAST(... AS ...)`.
     */
    private fun decodeCast(
        builder: RelBuilder,
        fn: FunctionCall,
        resultType: String,
    ): RexNode {
        require(fn.operandsCount == 1) { "cast expects exactly 1 operand, got ${fn.operandsCount}" }
        val operand = decode(builder, fn.operandsList[0])
        val targetType = builder.typeFactory.createSqlType(sqlTypeNameFor(resultType))
        return builder.rexBuilder.makeCast(targetType, operand)
    }

    /** Surface-type tag → the [SqlTypeName] the encoder used. Shared by parameter + cast decoding. */
    private fun sqlTypeNameFor(resultType: String): SqlTypeName =
        when (resultType) {
            "text" -> SqlTypeName.VARCHAR
            "int" -> SqlTypeName.BIGINT
            "float" -> SqlTypeName.DOUBLE
            "bool" -> SqlTypeName.BOOLEAN
            "datetime" -> SqlTypeName.TIMESTAMP
            else -> SqlTypeName.ANY
        }

    /**
     * Decode a [SubqueryExpression] back into a Calcite [RexSubQuery]. The nested plan is built
     * into a standalone RelNode that shares [builder]'s [org.apache.calcite.plan.RelOptCluster]
     * — Calcite requires a `RexSubQuery`'s `rel` to live in the same cluster as the outer
     * expression — via [PlanNodeDecoder.decodeSubrel] (push subtree, pop with `build()`, leaving
     * the outer stack balanced). Inverse of [encodeSubquery].
     */
    private fun decodeSubquery(
        builder: RelBuilder,
        sub: SubqueryExpression,
    ): RexNode {
        val subRel = PlanNodeDecoder.decodeSubrel(builder, sub.subquery)
        return when (sub.kind.lowercase()) {
            "scalar" -> RexSubQuery.scalar(subRel)
            "exists" -> RexSubQuery.exists(subRel)
            "in" ->
                RexSubQuery.`in`(
                    subRel,
                    com.google.common.collect.ImmutableList
                        .copyOf(sub.operandsList.map { decode(builder, it) }),
                )
            else -> throw UnsupportedOperationException(
                "Subquery kind '${sub.kind}' is not in the v1 wire format",
            )
        }
    }

    private fun decodeParameter(
        builder: RelBuilder,
        param: ParameterRef,
        resultType: String,
    ): RexNode {
        // Calcite's RexDynamicParam needs a RelDataType; surface-type tags map
        // to the same SqlTypeName the encoder used.
        val type = builder.typeFactory.createSqlType(sqlTypeNameFor(resultType))
        return builder.rexBuilder.makeDynamicParam(type, param.positionalIndex)
    }

    private fun operatorFor(opName: String): SqlOperator =
        when (opName.lowercase()) {
            "and" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.AND
            "or" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.OR
            "not" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.NOT
            "eq" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.EQUALS
            "ne" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.NOT_EQUALS
            "lt" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LESS_THAN
            "le" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LESS_THAN_OR_EQUAL
            "gt" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.GREATER_THAN
            "ge" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.GREATER_THAN_OR_EQUAL
            "add" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.PLUS
            "sub" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MINUS
            "mul" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.MULTIPLY
            "div" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.DIVIDE
            "is_null" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IS_NULL
            "is_not_null" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IS_NOT_NULL
            // Phase 08 B4 / DF-S05 + DF-DSL04 — first-class set/pattern membership.
            "in" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.IN
            "like" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.LIKE
            // String / scalar functions emitted by the encoder's `operator.name`
            // fallback (operationCode's else branch). These mirror exactly what
            // pattern SQL produces — `||` concat for LIKE-pattern building,
            // SUBSTRING for prefix tests, and unary minus for negated aggregates.
            "||" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.CONCAT
            "substring" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.SUBSTRING
            "-" -> org.apache.calcite.sql.`fun`.SqlStdOperatorTable.UNARY_MINUS
            else -> throw UnsupportedOperationException(
                "Operator '$opName' is not in the v1 wire format",
            )
        }

    internal fun surfaceTypeOf(t: RelDataType): String =
        when (t.sqlTypeName) {
            SqlTypeName.VARCHAR, SqlTypeName.CHAR -> "text"
            SqlTypeName.BOOLEAN -> "bool"
            SqlTypeName.INTEGER, SqlTypeName.BIGINT, SqlTypeName.SMALLINT, SqlTypeName.TINYINT -> "int"
            SqlTypeName.DECIMAL, SqlTypeName.DOUBLE, SqlTypeName.FLOAT, SqlTypeName.REAL -> "float"
            SqlTypeName.DATE, SqlTypeName.TIME, SqlTypeName.TIMESTAMP -> "datetime"
            else -> "unknown:${t.sqlTypeName}"
        }
}
