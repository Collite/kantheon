package org.tatrman.pinakes.compile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.kallimachos.v1.PageKind
import org.tatrman.pinakes.clients.PrometheusClient

/**
 * The compile outcome — the drafts + whether the LLM ran. `degraded = true` means
 * the run fell back to mechanical pages (budget exceeded or LLM error); the
 * corpus stays queryable but the wiki is thin for this source (architecture §14).
 */
data class CompileResult(
    val pages: List<PageDraft>,
    val degraded: Boolean,
    val llmCalled: Boolean,
)

/**
 * The COMPILE stage (architecture §7 — the DocWH differentiator): source parts →
 * LLM-authored ENTITY/CONCEPT/SUMMARY wiki pages (Prometheus). Prompt in
 * `prompts/compile-system.md`. Batch/offline — never on the query path.
 *
 * Parse-safe: a malformed LLM response does NOT crash the run — it degrades to a
 * single mechanical SUMMARY page over the parts (the "compile cost/quality" risk,
 * architecture §14: degrade to mechanical, the corpus stays queryable on parts).
 */
class WikiCompiler(
    private val prometheus: PrometheusClient,
    private val systemPrompt: String = loadPrompt(),
    // Per-run token budget (rough char/4 estimate). 0 = unbounded. Over budget →
    // degrade to mechanical (never blow the cost ceiling — architecture §14).
    private val tokenBudget: Int = 0,
) {
    private val log = LoggerFactory.getLogger(WikiCompiler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CompiledPage(
        val kind: String = "SUMMARY",
        val title: String = "",
        val contentMd: String = "",
        val derivedFromParts: List<Long> = emptyList(),
        val entityType: String = "",
        val entityLabel: String = "",
    )

    suspend fun compile(parts: List<PartInput>): CompileResult {
        if (parts.isEmpty()) return CompileResult(emptyList(), degraded = false, llmCalled = false)
        val userPrompt = parts.joinToString("\n\n") { "## part ${it.id}\n${it.text}" }

        val estimatedTokens = (systemPrompt.length + userPrompt.length) / CHARS_PER_TOKEN
        if (tokenBudget > 0 && estimatedTokens > tokenBudget) {
            log.warn("compile token budget exceeded ({} > {}), degrading to mechanical", estimatedTokens, tokenBudget)
            return CompileResult(listOf(mechanicalSummary(parts)), degraded = true, llmCalled = false)
        }

        val raw =
            try {
                prometheus.complete(systemPrompt, userPrompt)
            } catch (e: Exception) {
                log.warn("compile LLM call failed, degrading to mechanical summary: {}", e.message)
                return CompileResult(listOf(mechanicalSummary(parts)), degraded = true, llmCalled = true)
            }
        val parsed = parse(raw, parts)
        return if (parsed != null) {
            CompileResult(parsed, degraded = false, llmCalled = true)
        } else {
            CompileResult(listOf(mechanicalSummary(parts)), degraded = true, llmCalled = true)
        }
    }

    private fun parse(
        raw: String,
        parts: List<PartInput>,
    ): List<PageDraft>? =
        try {
            val compiled = json.decodeFromString<List<CompiledPage>>(raw.trim())
            if (compiled.isEmpty()) {
                null
            } else {
                compiled.mapIndexed { i, c -> c.toDraft(i, parts) }
            }
        } catch (e: Exception) {
            log.warn("compile output parse failed, degrading to mechanical summary: {}", e.message)
            null
        }

    private fun CompiledPage.toDraft(
        localId: Int,
        parts: List<PartInput>,
    ): PageDraft {
        val kind = runCatching { PageKind.valueOf(kind.uppercase()) }.getOrDefault(PageKind.SUMMARY)
        val derived = derivedFromParts.ifEmpty { parts.map { it.id } }
        val conceptRef =
            if ((kind == PageKind.ENTITY || kind == PageKind.CONCEPT) && entityLabel.isNotBlank()) {
                ConceptRefDraft(
                    entityType = entityType.ifBlank { "concept" },
                    entityId = "wiki:${entityLabel.lowercase().replace(Regex("\\s+"), "-")}",
                    displayLabel = entityLabel,
                )
            } else {
                null
            }
        return PageDraft(
            localId,
            kind,
            title.ifBlank { entityLabel.ifBlank { "Untitled" } },
            contentMd,
            derived,
            conceptRef,
        )
    }

    private fun mechanicalSummary(parts: List<PartInput>): PageDraft =
        PageDraft(
            localId = 0,
            kind = PageKind.SUMMARY,
            title =
                parts
                    .first()
                    .text
                    .take(60)
                    .substringBefore('\n'),
            contentMd = parts.joinToString("\n\n") { it.text },
            derivedFromParts = parts.map { it.id },
            conceptRef = null,
        )

    companion object {
        private const val CHARS_PER_TOKEN = 4

        fun loadPrompt(): String =
            WikiCompiler::class.java
                .getResourceAsStream("/prompts/compile-system.md")
                ?.bufferedReader()
                ?.readText()
                ?: error("compile-system.md prompt missing")
    }
}
