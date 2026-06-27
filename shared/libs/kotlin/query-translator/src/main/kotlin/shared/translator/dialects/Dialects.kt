package shared.translator.dialects

import org.tatrman.proteus.v1.SqlDialect as SqlDialectProto
import org.apache.calcite.sql.SqlDialect
import org.apache.calcite.sql.dialect.MssqlSqlDialect
import org.apache.calcite.sql.dialect.MysqlSqlDialect
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect

/**
 * Registry of supported Calcite [SqlDialect] instances.
 *
 * Calcite engagement rule #1: NEVER reference [MssqlSqlDialect.DEFAULT] (or any
 * other dialect singleton) directly from production code. Always go through
 * this registry. The MS SQL dialect's defaults assume `databaseMajorVersion = 0`
 * which silently turns off SQL Server 2012+ features (notably OFFSET/FETCH);
 * the registry constructs the dialect with `databaseMajorVersion = 11`
 * (SQL Server 2012) so OFFSET/FETCH stays enabled — which is what the
 * Phase 0 spike's RoundTripSpec relies on.
 *
 * The registry is a static object; instances are stateless and safe to share
 * across all per-query [shared.translator.framework.TranslatorFramework] uses.
 */
object Dialects {
    val MSSQL: SqlDialect =
        MssqlSqlDialectWithFloatCast(
            MssqlSqlDialect.DEFAULT_CONTEXT.withDatabaseMajorVersion(MSSQL_MAJOR_VERSION),
        )

    val POSTGRES: SqlDialect = PostgresqlSqlDialect.DEFAULT

    val MYSQL: SqlDialect = MysqlSqlDialect.DEFAULT

    /** Phase 08 A3 / DF-T04 — DuckDB dialect (Postgres-compatible for the constructs we emit). */
    val DUCKDB: SqlDialect = DuckDbSqlDialect.DEFAULT

    /**
     * Resolve the [SqlDialect] instance for the given proto enum.
     *
     * @throws IllegalArgumentException for `SQL_DIALECT_UNSPECIFIED`,
     *   `UNRECOGNIZED`, and any not-yet-supported dialect (SQLITE remains reserved in
     *   [SqlDialectProto] but isn't implemented at v1).
     */
    fun byCode(code: SqlDialectProto): SqlDialect =
        when (code) {
            SqlDialectProto.MSSQL -> MSSQL
            SqlDialectProto.POSTGRESQL -> POSTGRES
            SqlDialectProto.MYSQL_MARIADB -> MYSQL
            SqlDialectProto.DUCKDB -> DUCKDB
            SqlDialectProto.SQL_DIALECT_UNSPECIFIED ->
                throw IllegalArgumentException(
                    "SqlDialect not specified — caller must pick one (MSSQL is the v1 default)",
                )
            SqlDialectProto.UNRECOGNIZED ->
                throw IllegalArgumentException("Unrecognised proto SqlDialect value")
        }

    private const val MSSQL_MAJOR_VERSION = 11
}
