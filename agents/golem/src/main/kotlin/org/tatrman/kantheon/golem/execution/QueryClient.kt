package org.tatrman.kantheon.golem.execution

import kotlinx.serialization.json.JsonArray

/** Column metadata from a `query` result (`structuredContent.columns`). */
data class ColumnMeta(
    val name: String,
    val type: String,
    val nullable: Boolean,
)

/** A `query` tool result — rows (JSON array of objects) + metadata. */
data class QueryResult(
    val rows: JsonArray,
    val columns: List<ColumnMeta>,
    val rowCount: Long,
    val truncated: Boolean,
)

/** A `compile` tool result — the SQL the FREE_SQL pre-check produced. */
data class CompileResult(
    val compiledSql: String,
    val appliedSecurity: Boolean,
)

/** A theseus-mcp call failed (transport, pipeline error, or fail-closed RLS denial). */
class QueryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Golem's edge to the forked query path (**theseus-mcp**). The caller's OBO
 * [bearer] is forwarded on every call — never a service identity (PD-8,
 * kantheon-security §2); a null bearer is a misconfiguration, not "anonymous".
 * The interface is the boundary; tests drive a fake, the real impl is the MCP
 * streamable-HTTP client (Stage 2.4 T3).
 */
interface QueryClient {
    suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult

    /** FREE_SQL pre-check: compile source to the target dialect before running it. */
    suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult
}
