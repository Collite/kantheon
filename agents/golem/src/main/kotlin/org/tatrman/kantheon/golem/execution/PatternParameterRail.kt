package org.tatrman.kantheon.golem.execution

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.tatrman.ariadne.v1.ModelBundleQuery
import org.tatrman.kantheon.patternparams.ParamSpec
import org.tatrman.kantheon.patternparams.PatternParams
import org.tatrman.kantheon.patternparams.TypedParam

/**
 * The pattern-parametrization rail (Golem S2.4 §10 Δ1). Given a pattern query's
 * declared parameters and the raw args a plan carried, it produces the typed
 * `{name: {value, type}}` JSON the query edge expects — and the pattern's
 * `sql_template` travels **verbatim** with `{name}` intact (Proteus's now-restored
 * ParameterBridge rewrites `{name}` → `?` downstream; T7). Nothing is inlined.
 *
 * Two pre-execution gates, both **fail-closed** (never forward an under-bound query):
 *   * **missing-required** — a declared, non-optional param absent from the args.
 *     Surfaced for the param_fill clarification rail (S3.2).
 *   * **`PATTERN_PARAM_UNFILLED`** (T9) — any residual `{name}` in the template with
 *     no typed binding (a template/model typo, or an unsupplied optional placeholder).
 */
sealed interface RailResult {
    /** Happy path — the typed `{name:{value,type}}` parameters JSON for `query.parameters`. */
    data class Bound(
        val parametersJson: String,
    ) : RailResult

    /** A required param is unbound — the caller asks the user (param_fill, S3.2). */
    data class MissingRequired(
        val names: List<String>,
    ) : RailResult

    /** A `{name}` in the template has no binding — never forwarded (`PATTERN_PARAM_UNFILLED`). */
    data class Unfilled(
        val placeholders: List<String>,
    ) : RailResult
}

/**
 * A pattern query reached the execution boundary under-bound — a residual `{name}` with no typed
 * binding ([RailResult.Unfilled]) or a missing required param ([RailResult.MissingRequired]). Thrown
 * **before** any query is forwarded to theseus, so an unbound token never reaches the edge. The
 * executor turns it into a `STATUS_FAILED` turn carrying [errorCode]. (S3.2 intercepts the
 * missing-required case earlier and asks the user via param_fill instead of failing.)
 */
class PatternParamUnfilledException(
    val placeholders: List<String>,
    val errorCode: String = PatternParameterRail.UNFILLED_ERROR_CODE,
) : RuntimeException("pattern parameter(s) unfilled: ${placeholders.joinToString()} ($errorCode)")

/**
 * A required pattern parameter is unbound (Δ2). Unlike [PatternParamUnfilledException] (a
 * template typo → fail closed), this is recoverable: the executor surfaces it so the turn can
 * **ask the user** for the value via a `param_fill` clarification and resume. Carries the first
 * missing param's name + label and the pattern id whose plan must be re-bound on resume.
 */
class ParamFillNeededException(
    val paramName: String,
    val label: String,
) : RuntimeException("required pattern parameter '$paramName' is unbound — needs param_fill")

object PatternParameterRail {
    const val UNFILLED_ERROR_CODE: String = "PATTERN_PARAM_UNFILLED"

    // `{name}` — a single curly placeholder, the form ParameterBridge rewrites.
    private val PLACEHOLDER = Regex("""\{([a-zA-Z_][a-zA-Z0-9_]*)}""")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Prepare the typed parameters for one pattern query. [rawArgsJson] is the plan's
     * `params_json` (a `{name: value}` object, possibly blank/`{}`); [pq] is the catalog entry.
     */
    fun prepare(
        rawArgsJson: String,
        pq: ModelBundleQuery,
    ): RailResult {
        val specs =
            pq.parametersList.map {
                // A param is omittable when it's flagged `optional` OR carries a `default_value`
                // (the proto admits both as optionality markers — Ariadne may emit either). Keying
                // off only `optional` would mis-classify a defaulted param as missing-required and
                // trigger a spurious param_fill.
                ParamSpec(
                    name = it.name,
                    type = it.type,
                    label = it.label,
                    optional = it.optional || it.hasDefaultValue(),
                )
            }
        val rawArgs = parseArgs(rawArgsJson)
        val bind = PatternParams.buildPatternParameters(rawArgs, specs)
        if (bind.missingRequired.isNotEmpty()) {
            return RailResult.MissingRequired(bind.missingRequired)
        }
        // Fail-fast guard: any {name} in the template with no typed binding must NOT reach the
        // query edge as an unbound token (an opaque downstream parse error). Surface it locally.
        val unfilled =
            PLACEHOLDER
                .findAll(pq.sourceText)
                .map { it.groupValues[1] }
                .filter { it !in bind.parameters }
                .distinct()
                .toList()
        if (unfilled.isNotEmpty()) {
            return RailResult.Unfilled(unfilled)
        }
        return RailResult.Bound(toParametersJson(bind.parameters))
    }

    /** Parse the plan's raw `{name: value}` args (blank / `{}` / malformed → empty). */
    private fun parseArgs(rawArgsJson: String): Map<String, Any?> {
        if (rawArgsJson.isBlank() || rawArgsJson == "{}") return emptyMap()
        val obj = runCatching { json.parseToJsonElement(rawArgsJson).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, el) -> jsonScalar(el) }
    }

    private fun jsonScalar(el: kotlinx.serialization.json.JsonElement): Any? =
        when (el) {
            is JsonNull -> null
            // Preserve native JSON scalar types (a JSON `true`/`123` vs the strings `"true"`/`"123"`)
            // so coercion downstream sees the real type rather than everything stringified.
            is JsonPrimitive ->
                if (el.isString) {
                    el.content
                } else {
                    el.booleanOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.content
                }
            else -> el.toString()
        }

    /** Serialise the typed map to `{name: {value, type}}` JSON for the query edge. */
    private fun toParametersJson(parameters: Map<String, TypedParam>): String =
        buildJsonObject {
            for ((name, tp) in parameters) {
                put(
                    name,
                    buildJsonObject {
                        put("value", scalarToJson(tp.value))
                        put("type", tp.type)
                    },
                )
            }
        }.let { JsonObject(it).toString() }

    private fun scalarToJson(value: Any?): JsonPrimitive =
        when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}
