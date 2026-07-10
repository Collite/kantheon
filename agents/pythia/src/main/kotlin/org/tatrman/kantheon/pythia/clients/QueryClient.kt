package org.tatrman.kantheon.pythia.clients

import kotlinx.serialization.json.JsonArray

/** A column's metadata from a query result. */
data class ColumnMeta(
    val name: String,
    val type: String,
    val nullable: Boolean,
)

/** A query result: rows + metadata + forwarded `pipeline_warnings` (Rule-6 channel). */
data class QueryResult(
    val rows: JsonArray,
    val columns: List<ColumnMeta>,
    val rowCount: Long,
    val truncated: Boolean,
    val warnings: List<String> = emptyList(),
)

/** Result of compiling a composed stack before running it. */
data class CompileResult(
    val compiledSql: String,
    val appliedSecurity: Boolean,
    val warnings: List<String> = emptyList(),
)

/** A non-auth query failure (transport / engine error). */
class QueryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The query edge — query-mcp (compile + run). Every call carries the user's OBO
 * bearer (PD-8); a null/expired bearer fails closed. The real impl is
 * [QueryQueryClient]; tests inject a fake.
 */
interface QueryClient {
    suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult

    suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult
}
