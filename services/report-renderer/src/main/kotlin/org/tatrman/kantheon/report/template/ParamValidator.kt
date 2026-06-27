package org.tatrman.kantheon.report.template

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.kantheon.report.v1.ParamDef
import org.tatrman.kantheon.report.v1.ParamKind
import java.time.LocalDate

/** A validated, defaults-applied parameter map, or the list of validation errors. */
sealed interface ValidationResult {
    data class Ok(
        val args: Map<String, String>,
    ) : ValidationResult

    data class Invalid(
        val errors: List<String>,
    ) : ValidationResult
}

/**
 * Validates a render request's `args_json` (Rule 7 — args as a JSON string) against a
 * template's [ParamDef]s (Stage 3.4 T2): required-presence, kind-shape (INT numeric, DATE
 * parseable, PERIOD in the allowed set), and applies declared defaults for absent optionals.
 */
object ParamValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val PERIODS = setOf("ytd", "mtd", "qtd", "all")

    fun validate(
        argsJson: String,
        params: List<ParamDef>,
    ): ValidationResult {
        val obj =
            runCatching { json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject }.getOrNull()
                ?: return ValidationResult.Invalid(listOf("args_json is not a JSON object"))

        val errors = mutableListOf<String>()
        val out = mutableMapOf<String, String>()
        for (p in params) {
            val raw = (obj[p.name] as? JsonPrimitive)?.content?.ifBlank { null }
            val value = raw ?: p.defaultValue.ifBlank { null }
            if (value == null) {
                if (p.required) errors += "missing required parameter '${p.name}'"
                continue
            }
            val kindError = checkKind(p, value)
            if (kindError != null) errors += kindError else out[p.name] = value
        }
        return if (errors.isEmpty()) ValidationResult.Ok(out) else ValidationResult.Invalid(errors)
    }

    private fun checkKind(
        p: ParamDef,
        value: String,
    ): String? =
        when (p.kind) {
            ParamKind.PARAM_INT ->
                if (value.toLongOrNull() == null) "parameter '${p.name}' must be an integer" else null
            ParamKind.PARAM_DATE ->
                if (runCatching {
                        LocalDate.parse(
                            value,
                        )
                    }.isFailure
                ) {
                    "parameter '${p.name}' must be an ISO date"
                } else {
                    null
                }
            ParamKind.PARAM_PERIOD ->
                if (value.lowercase() !in PERIODS && !value.contains("..")) {
                    "parameter '${p.name}' must be ytd|mtd|qtd|all or an explicit range"
                } else {
                    null
                }
            else -> null // string / id kinds: any non-blank value
        }
}
