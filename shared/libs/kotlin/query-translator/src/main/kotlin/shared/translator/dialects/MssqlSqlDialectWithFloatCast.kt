package shared.translator.dialects

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.sql.SqlAlienSystemTypeNameSpec
import org.apache.calcite.sql.SqlDataTypeSpec
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.parser.SqlParserPos
import org.apache.calcite.sql.type.SqlTypeName

/**
 * MS SQL Server dialect that fixes CAST target type names Calcite renders with
 * ANSI names SQL Server rejects.
 *
 * Calcite's [MssqlSqlDialect] emits `CAST(... AS DOUBLE)` for [SqlTypeName.DOUBLE],
 * but SQL Server has no `DOUBLE` type — its 8-byte double-precision type is
 * `FLOAT`. The bad cast produced `Incorrect syntax near ')'` at the database.
 * We override [getCastSpec] to render `DOUBLE` as `FLOAT`; every other type
 * defers to the stock dialect.
 *
 * Reach this only through the [Dialects] registry (Calcite engagement rule #1).
 */
class MssqlSqlDialectWithFloatCast(
    context: SqlDialect.Context,
) : MssqlSqlDialect(context) {
    override fun getCastSpec(type: RelDataType): SqlNode? =
        when (type.sqlTypeName) {
            SqlTypeName.DOUBLE ->
                SqlDataTypeSpec(
                    SqlAlienSystemTypeNameSpec("FLOAT", type.sqlTypeName, SqlParserPos.ZERO),
                    SqlParserPos.ZERO,
                )
            else -> super.getCastSpec(type)
        }
}
