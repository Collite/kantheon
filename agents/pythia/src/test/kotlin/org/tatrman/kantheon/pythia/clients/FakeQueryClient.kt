package org.tatrman.kantheon.pythia.clients

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.pythia.executor.TokenExpiredException

/** Scripted [QueryClient] for executor/evaluator tests (no live query edge). */
class FakeQueryClient(
    private val rows: JsonArray = Json.parseToJsonElement("[]") as JsonArray,
    private val warnings: List<String> = emptyList(),
    private val throwTokenExpiry: Boolean = false,
    private val truncated: Boolean = false,
) : QueryClient {
    var lastParamsJson: String? = null
    var lastBearer: String? = null
    var compiled: Boolean = false

    override suspend fun compile(
        source: String,
        sourceLanguage: String,
        targetDialect: String,
        bearer: String?,
    ): CompileResult {
        compiled = true
        return CompileResult("SELECT 1", appliedSecurity = true, warnings = warnings)
    }

    override suspend fun query(
        source: String,
        sourceLanguage: String,
        paramsJson: String,
        rowLimit: Int?,
        bearer: String?,
    ): QueryResult {
        lastParamsJson = paramsJson
        lastBearer = bearer
        if (throwTokenExpiry) throw TokenExpiredException("token expired mid-run")
        return QueryResult(rows, emptyList(), rows.size.toLong(), truncated, warnings)
    }
}
