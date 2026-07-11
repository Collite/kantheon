package org.tatrman.kantheon.golem.format

import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.kantheon.envelope.v1.PromptChip
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding

/**
 * Pattern-derived (sibling) chip builder — a faithful port of ai-platform
 * `chips/pattern_derived.py`. Suggests up to five OTHER catalog patterns whose
 * parameters the current turn's entity bindings can fill, prefilled from those
 * bindings.
 *
 * Overlap is **parameter-name intersection** against the binding pool — exact /
 * substring / `_UNIVERSAL_SYNONYMS` bridge — because `PatternQueryDesc.entities`
 * is empty upstream (so true entity-overlap isn't available).
 */
object PatternDerivedChips {
    private val PARAM_RE = Regex("""\{(?<name>[a-zA-Z_][a-zA-Z0-9_]*)}""")

    private val UNIVERSAL_SYNONYMS: Map<String, List<String>> =
        mapOf(
            "date" to listOf("obdobi", "rok", "datum", "period"),
            "money" to listOf("castka", "hodnota"),
            "person" to listOf("osoba", "user"),
            "organization" to listOf("subjekt", "firma"),
        )

    fun derive(
        pickedPatternId: String?,
        bindings: List<EntityBinding>,
        model: ModelSnapshot?,
    ): List<PromptChip> {
        if (model == null) return emptyList()
        val pool = bindingPool(bindings)
        if (pool.isEmpty()) return emptyList()

        val chips = mutableListOf<PromptChip>()
        for (pq in model.patternQueries) {
            if (chips.size >= 5) break
            val shortId = pq.objectDescriptor.localName
            if (shortId == pickedPatternId) continue
            val filled = tryFillArgs(pq, pool)
            if (filled.isEmpty()) continue
            val example =
                pq.queryDescriptor.search.examplesList
                    .firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: continue
            val display = exampleWithArgs(example, filled)
            chips +=
                PromptChip
                    .newBuilder()
                    .setDisplay(display)
                    .setPrompt(display)
                    .setSource("pattern_derived")
                    .setPatternId(shortId)
                    .setPrefilledArgsJson(argsJson(filled))
                    .build()
        }
        return chips
    }

    /** name → value, keyed by domain `entity_type_ref` and universal type name (all lower-cased). */
    private fun bindingPool(bindings: List<EntityBinding>): Map<String, String> {
        val pool = LinkedHashMap<String, String>()
        for (b in bindings) {
            when {
                b.hasDomain() -> {
                    val key = b.domain.entityTypeRef.lowercase()
                    val value = b.domain.resolvedLabel.ifBlank { b.domain.rawText }
                    if (key.isNotBlank() && value.isNotBlank()) pool.putIfAbsent(key, value)
                }
                b.hasUniversal() -> {
                    val key =
                        b.universal.entityType.name
                            .lowercase()
                    val value = b.universal.normalizedValue.ifBlank { b.universal.rawText }
                    if (key.isNotBlank() && value.isNotBlank()) pool.putIfAbsent(key, value)
                }
            }
        }
        return pool
    }

    private fun tryFillArgs(
        pq: ModelBundleQuery,
        pool: Map<String, String>,
    ): Map<String, String> {
        val filled = LinkedHashMap<String, String>()
        for (p in pq.parametersList) {
            val pname = normalize(p.name)
            val match =
                pool.keys.firstOrNull { k ->
                    val klow = normalize(k)
                    overlaps(klow, pname) ||
                        UNIVERSAL_SYNONYMS[klow]?.any { overlaps(it, pname) } == true
                }
            if (match != null) filled[p.name] = pool.getValue(match)
        }
        return filled
    }

    /**
     * Two parameter/binding names overlap when they're equal or share an underscore-delimited
     * segment — a word-boundary test, not a raw substring one (so `rok` no longer matches
     * `prokladani`, while `rok` still matches `ucetni_rok`).
     */
    private fun overlaps(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        val aSeg = a.split("_").filter { it.isNotBlank() }.toSet()
        val bSeg = b.split("_").filter { it.isNotBlank() }.toSet()
        return aSeg.any { it in bSeg }
    }

    private fun exampleWithArgs(
        example: String,
        filled: Map<String, String>,
    ): String = PARAM_RE.replace(example) { m -> filled[m.groups["name"]!!.value] ?: m.value }

    private fun argsJson(filled: Map<String, String>): String =
        filled.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${quote(v)}" }

    private fun quote(s: String): String = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun normalize(s: String): String = s.trim().lowercase().replace("-", "_")
}
