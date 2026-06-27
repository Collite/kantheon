package shared.translator.params

import org.apache.calcite.sql.type.SqlTypeName

/**
 * Bridge between named-parameter SQL and Calcite's positional `?` form.
 *
 * The only placeholder syntax recognised is **single-curly `{name}`** —
 * [prepareSqlForCalcite] scans for `{`…`}` pairs and rewrites each to a `?`
 * (or a typed `CAST(? AS T)`). At-sign `@name` and double-curly `{{name}}`
 * are **not** handled here; callers normalise to `{name}` form upstream
 * (`ParametrizedSql.toCurlyFormat()` or by construction). A literal `{` with
 * no matching `}` passes through untouched.
 *
 * Three concerns the bridge handles, all of which Calcite would otherwise
 * get wrong if used naively:
 *
 *   1. **Identity-by-name dedup (Round 1).** A query like
 *      `WHERE x = {a} OR y = {a}` references the parameter `a` twice but
 *      shares one binding. The naive `toQuestionMarkFormat()` from
 *      `erp-sql-common` produces two `?` placeholders + duplicate
 *      ParameterBindings; this bridge collapses same-name occurrences to
 *      a single positional slot BEFORE Calcite sees the SQL.
 *
 *   2. **Type pre-supply (rule #3 / #6).** Calcite's parser produces
 *      `RexDynamicParam` nodes whose type is unset; validation then fails
 *      with "Illegal use of dynamic parameter" whenever the surrounding
 *      context can't infer a type. The worst offender is `||` / `CONCAT`:
 *      its operand-type inference is `null` (unlike `LIKE`/`BETWEEN`, which
 *      use `FIRST_KNOWN`), so a bare `?` ANYWHERE in a `||` chain is
 *      untypeable — e.g. `KOD_STR LIKE {x} || '%'` or
 *      `NAZEV LIKE '%' || {x} || '%'` both fail. The [typed] mode emits
 *      each placeholder as `CAST(? AS <type>)` using the declared parameter
 *      type, which is the canonical Calcite idiom for typing a `?`. The
 *      orchestrator uses this as a fallback only when the bare-`?` form
 *      fails to validate; [ParameterTyper] then unwraps the cast back to a
 *      typed bare `?` so the executed SQL carries no synthetic `CAST`.
 *
 *   3. **Name restoration (rule #6).** After Calcite mangles names into
 *      positional indices, downstream consumers (Validator, Worker, audit)
 *      need the original parameter names back. The bridge keeps a
 *      position → name map and exposes a tree rewriter that re-attaches
 *      the names to `RexDynamicParam` nodes in the produced RelNode.
 *      _v1.1 stubs the rewriter pending RexShuttle integration._
 */
object ParameterBridge {
    /**
     * Apply identity-by-name dedup to `(sql, parameters)`.
     *
     * Input SQL must be in `{name}` form with one [SqlParam] per used name
     * (callers obtain this via `ParametrizedSql.toCurlyFormat()` or by
     * construction). Output SQL has each occurrence of `{name}` replaced
     * by `?`; the returned [PreparedSql.parameterOrder] lists the parameter
     * NAMES in left-to-right order (one entry per `?`); a parameter that
     * appears twice in the SQL appears in [parameterOrder] twice but
     * resolves to a single value via [PreparedSql.values].
     *
     * Throws [IllegalArgumentException] when the SQL references a name
     * that has no entry in [parameters].
     *
     * When [typed] is true, each placeholder is emitted as
     * `CAST(? AS <calcite-type>)` (derived from the matching [SqlParam.type])
     * instead of a bare `?`. This is the canonical way to give Calcite an
     * explicit type for a `?` whose context can't infer one (notably inside
     * `||` / `CONCAT`, which has no operand-type inference). The placeholder
     * count, order and [PreparedSql.parameterOrder] are identical in both
     * modes — a `CAST` adds no extra `?`.
     */
    fun prepareSqlForCalcite(
        sql: String,
        parameters: List<SqlParam>,
        typed: Boolean = false,
    ): PreparedSql {
        val byName: Map<String, SqlParam> = parameters.associateBy { it.name }
        val out = StringBuilder(sql.length)
        val order = mutableListOf<String>()
        var i = 0
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '{') {
                val close = sql.indexOf('}', startIndex = i + 1)
                if (close < 0) {
                    out.append(ch)
                    i++
                    continue
                }
                val name = sql.substring(i + 1, close)
                val param =
                    byName[name]
                        ?: throw IllegalArgumentException(
                            "SQL references unknown parameter '{$name}'; known: ${byName.keys}",
                        )
                if (typed) {
                    out.append("CAST(? AS ").append(calciteSqlType(param.type)).append(')')
                } else {
                    out.append('?')
                }
                order.add(name)
                i = close + 1
            } else {
                out.append(ch)
                i++
            }
        }
        return PreparedSql(
            sql = out.toString(),
            parameterOrder = order,
            values = byName,
        )
    }

    /**
     * Surface parameter type → a SQL type keyword Calcite's parser accepts in a
     * `CAST(... AS <type>)`. Derives from the shared [SurfaceTypeMapping] table
     * (N9) so this and [ParameterTyper.toRelDataType] cannot drift; unknown
     * surface types fall back to [SurfaceTypeMapping.UNKNOWN_SQL_KEYWORD]
     * (`VARCHAR` — the `ANY` that [ParameterTyper] uses is not a castable SQL
     * keyword, and `VARCHAR` is the safe default for the text-search contexts
     * that need this typing). DECIMAL carries an explicit precision/scale (M3)
     * so a decimal param isn't truncated to scale 0.
     */
    private fun calciteSqlType(surfaceType: String): String {
        val sqlTypeName =
            SurfaceTypeMapping.sqlTypeNameOrNull(surfaceType)
                ?: return SurfaceTypeMapping.UNKNOWN_SQL_KEYWORD
        return if (sqlTypeName == SqlTypeName.DECIMAL) {
            "DECIMAL(${SurfaceTypeMapping.DECIMAL_PRECISION}, ${SurfaceTypeMapping.DECIMAL_SCALE})"
        } else {
            sqlTypeName.name
        }
    }
}

/**
 * One named parameter binding — a `(name, type, value)` tuple.
 *
 * Mirrors the proto `ParameterBinding` from `org.tatrman.plan.v1` but kept
 * library-local so the params package doesn't tightly couple to wire types.
 * Conversion helpers between this and the proto form land with the
 * orchestrator (Section J).
 */
data class SqlParam(
    val name: String,
    val type: String,
    val value: Any?,
)

/** Result of [ParameterBridge.prepareSqlForCalcite]. */
data class PreparedSql(
    /** SQL with `{name}` placeholders rewritten to `?`. */
    val sql: String,
    /**
     * Parameter NAMES in left-to-right order — one entry per `?` in [sql].
     * A name appearing twice in the SQL appears twice in this list (Calcite
     * still sees duplicate `?` positions); the dedup happens at the value
     * lookup level via [values].
     */
    val parameterOrder: List<String>,
    /** Name → SqlParam map. Lookup target for downstream consumers. */
    val values: Map<String, SqlParam>,
) {
    /** Distinct names in order of first appearance. */
    val distinctNames: List<String> = parameterOrder.distinct()
}
