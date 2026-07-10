// Pattern parameter binding — a faithful port of ai-platform's `aip_pattern_params`
// (Python stdlib lib). Stdlib only, no proto/agent coupling, so Golem, Pythia and
// Wrangler can all reuse it (Golem S2.4 §10 Δ1).
//
// The job: given the **raw args** a plan carried (LLM/Resolver/selection-derived) and a
// pattern's **declared parameters**, produce the typed `{name: {value, type}}` map the
// query edge (query-mcp `query.parameters`) expects, plus the list of required params
// that went unbound. Values are coerced to the surface type's shape; keys are normalised
// onto the declared names (exact → case-insensitive → label-token alias → fuzzy). The
// pattern's `sql_template` is sent **verbatim** with `{name}` intact — Translate's
// ParameterBridge rewrites `{name}` → `?` downstream; nothing is inlined here.
package org.tatrman.kantheon.patternparams

/** One declared pattern parameter — duck-typed mirror of the Python `ParamSpec`/`PatternParam`. */
data class ParamSpec(
    val name: String,
    /** Surface DSL type: varchar|int/integer/bigint|decimal/numeric/float/double|bool/boolean|date/datetime/timestamp. */
    val type: String,
    val label: String = "",
    val optional: Boolean = false,
)

/** A coerced, typed value bound to a parameter — the `{value, type}` pair on the wire. */
data class TypedParam(
    val value: Any?,
    val type: String,
)

/** Result of [PatternParams.buildPatternParameters]. */
data class ParamBindResult(
    /** `name -> {value, type}` — the typed parameters map for the query edge. */
    val parameters: Map<String, TypedParam>,
    /** Declared, non-optional names absent from the supplied args. Caller decides clarify-vs-error. */
    val missingRequired: List<String>,
)

object PatternParams {
    /** Surface DSL type → `parameters.proto` tag (`text|int|float|bool|datetime`). */
    fun typeTag(surfaceType: String): String =
        when (surfaceType.trim().lowercase()) {
            "varchar", "char", "text", "string", "nvarchar" -> "text"
            "int", "integer", "bigint", "smallint", "tinyint" -> "int"
            "decimal", "numeric", "float", "double", "real" -> "float"
            "bool", "boolean", "bit" -> "bool"
            "date", "datetime", "timestamp" -> "datetime"
            else -> "text" // safe default
        }

    /**
     * Coerce a raw value to a shape appropriate for the surface type tag.
     *
     * `null` passes through as `null` (a SQL NULL bind) rather than being stringified to the
     * literal "null" — otherwise a drill-down on a NULL key column, or an LLM that emits a JSON
     * null, would filter on the text "null".
     */
    fun coerceValue(
        value: Any?,
        surfaceType: String,
    ): Any? {
        if (value == null) return null
        return when (typeTag(surfaceType)) {
            "int" -> value.toString().toLongOrNull() ?: value.toString()
            "float" -> value.toString().toDoubleOrNull() ?: value.toString()
            "bool" ->
                when (value) {
                    is Boolean -> value
                    else ->
                        when (value.toString().lowercase().trim()) {
                            "true", "1", "yes" -> true
                            "false", "0", "no" -> false
                            else -> value.toString().isNotEmpty()
                        }
                }
            // text / datetime / unknown → string
            else -> value.toString()
        }
    }

    /**
     * Map raw arg keys onto declared param names.
     *
     * Match order: exact → case-insensitive → label-token alias → fuzzy (ratio ≥ 0.8 against the
     * full name and each underscore-separated segment). Returns `(normalised, unmapped)`.
     */
    fun normaliseArgKeys(
        args: Map<String, Any?>,
        params: List<ParamSpec>,
    ): Pair<Map<String, Any?>, List<String>> {
        if (params.isEmpty() || args.isEmpty()) return args.toMap() to emptyList()

        val lowerToName: Map<String, String> = params.associate { it.name.lowercase() to it.name }
        val exactNames: Set<String> = lowerToName.values.toSet()

        // Label-token aliases: split each param's label on whitespace/punctuation, keep tokens ≥ 4.
        val labelAliases = LinkedHashMap<String, String>()
        for (p in params) {
            val label = p.label
            if (label.isNotEmpty()) {
                for (raw in label.lowercase().split(Regex("[\\s,;/()\\-]+"))) {
                    val tok = raw.trim()
                    if (tok.length >= 4) labelAliases.putIfAbsent(tok, p.name)
                }
            }
        }

        val normalised = LinkedHashMap<String, Any?>()
        val unmapped = mutableListOf<String>()

        for ((key, value) in args) {
            val kLower = key.lowercase()

            // 1. Exact match (declared name as-is).
            if (key in exactNames) {
                normalised[key] = value
                continue
            }
            // 2. Case-insensitive exact.
            val ciName = lowerToName[kLower]
            if (ciName != null) {
                normalised[ciName] = value
                continue
            }
            // 3. Label-token alias.
            val aliasName = labelAliases[kLower]
            if (aliasName != null) {
                normalised[aliasName] = value
                continue
            }
            // 4. Fuzzy: against the full name and underscore-separated segments (len ≥ 3).
            var bestRatio = 0.0
            var bestName: String? = null
            for (p in params) {
                val r = sequenceRatio(kLower, p.name.lowercase())
                if (r > bestRatio) {
                    bestRatio = r
                    bestName = p.name
                }
                for (seg in p.name.split("_")) {
                    if (seg.length < 3) continue
                    val rs = sequenceRatio(kLower, seg.lowercase())
                    if (rs > bestRatio) {
                        bestRatio = rs
                        bestName = p.name
                    }
                }
            }
            if (bestRatio >= 0.8 && bestName != null) {
                normalised[bestName] = value
            } else {
                unmapped.add(key)
            }
        }
        return normalised to unmapped
    }

    /**
     * Build the typed `{name: {value, type}}` map for the query edge plus the list of missing
     * **required** param names. Does NOT raise on missing — the caller decides whether to clarify
     * (param_fill) or fail. Optional params absent from args are simply omitted.
     */
    fun buildPatternParameters(
        args: Map<String, Any?>,
        params: List<ParamSpec>,
    ): ParamBindResult {
        val (normalised, _) = normaliseArgKeys(args, params)
        val parameters = LinkedHashMap<String, TypedParam>()
        val missingRequired = mutableListOf<String>()
        for (p in params) {
            if (p.name in normalised) {
                parameters[p.name] = TypedParam(coerceValue(normalised[p.name], p.type), p.type)
            } else if (!p.optional) {
                missingRequired.add(p.name)
            }
        }
        return ParamBindResult(parameters = parameters, missingRequired = missingRequired)
    }

    /**
     * Ratcliff/Obershelp similarity ratio — the algorithm Python's
     * `difflib.SequenceMatcher.ratio()` uses: `2 * M / T`, where `M` is the total number of
     * matched characters found by recursively taking the longest contiguous matching block and
     * recursing on the unmatched left/right remainders, and `T` is the combined length of both
     * strings. Ported here (rather than approximated) so the ≥ 0.8 fuzzy threshold behaves as it
     * did in the Python rail.
     */
    internal fun sequenceRatio(
        a: String,
        b: String,
    ): Double {
        val total = a.length + b.length
        if (total == 0) return 1.0
        return 2.0 * matchedChars(a, b) / total
    }

    private fun matchedChars(
        a: String,
        b: String,
    ): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val (aStart, bStart, size) = longestMatch(a, b)
        if (size == 0) return 0
        return size +
            matchedChars(a.substring(0, aStart), b.substring(0, bStart)) +
            matchedChars(a.substring(aStart + size), b.substring(bStart + size))
    }

    /** Longest contiguous matching block between [a] and [b] → (aStart, bStart, size). */
    private fun longestMatch(
        a: String,
        b: String,
    ): Triple<Int, Int, Int> {
        var bestI = 0
        var bestJ = 0
        var bestSize = 0
        // j2len[j] = length of the longest match ending at a[i-1], b[j-1].
        var j2len = IntArray(b.length + 1)
        for (i in a.indices) {
            val newJ2len = IntArray(b.length + 1)
            for (j in b.indices) {
                if (a[i] == b[j]) {
                    val k = j2len[j] + 1
                    newJ2len[j + 1] = k
                    if (k > bestSize) {
                        bestI = i - k + 1
                        bestJ = j - k + 1
                        bestSize = k
                    }
                }
            }
            j2len = newJ2len
        }
        return Triple(bestI, bestJ, bestSize)
    }
}
