package org.tatrman.pinakes.resolve

import java.text.Normalizer

/**
 * Conservative, deterministic label conformance for global entity resolution
 * (architecture §7 — "Kaufland is one node regardless of feed"). Raw
 * `displayLabel.lowercase()` keying fragments an entity across feed spelling
 * variants ("Kaufland", "Kaufland s.r.o.", "KAUFLAND  CZ"); this folds the
 * mechanical variants that are safe to merge without semantic risk:
 *
 *  - case, surrounding/au internal whitespace, trailing punctuation
 *  - diacritics (NFD strip — Czech "Plzeň" == "Plzen")
 *  - a closed set of trailing legal-form suffixes (s.r.o., a.s., spol. s r.o., …)
 *
 * It deliberately does NOT do fuzzy/edit-distance matching — that is the fuzzy matcher.s job
 * (the Czech-aware fuzzy matcher) and is the deploy-path upgrade for the
 * Kallimachos-backed index. This keeps the in-process index honest without
 * over-merging distinct entities.
 */
object LabelNormalizer {
    private val legalSuffixes =
        listOf(
            "spol. s r.o.",
            "spol s r o",
            "s.r.o.",
            "s r o",
            "a.s.",
            "a s",
            "k.s.",
            "v.o.s.",
            "o.s.",
            "z.s.",
            "se",
            "ltd.",
            "ltd",
            "inc.",
            "inc",
            "llc",
            "plc",
            "gmbh",
            "co.",
            "corp.",
            "corp",
        )

    fun normalize(label: String): String {
        val deaccented =
            Normalizer
                .normalize(label, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
        var s = deaccented.lowercase().replace(Regex("\\s+"), " ").trim()
        // Strip a single trailing legal-form suffix (after a comma or space).
        for (suffix in legalSuffixes) {
            val candidate = s.removeSuffix(suffix).trimEnd(',', ' ', '.')
            if (candidate != s && candidate.isNotBlank()) {
                s = candidate
                break
            }
        }
        return s.trimEnd(',', ' ', '.').trim()
    }
}
