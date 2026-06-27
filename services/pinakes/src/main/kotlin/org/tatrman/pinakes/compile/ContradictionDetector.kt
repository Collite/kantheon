package org.tatrman.pinakes.compile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.tatrman.kallimachos.v1.EdgeKind
import org.tatrman.pinakes.clients.PrometheusClient
import org.tatrman.pinakes.resolve.ResolvedPage

/**
 * The contradiction-flag pass (P3 Stage 3.3 T1): asks Prometheus whether any
 * pages assert conflicting facts and writes `CONTRADICTS` edges (contracts §1).
 * Parse-safe — a malformed/failed detection yields no contradictions (never
 * crashes the run).
 */
class ContradictionDetector(
    private val prometheus: PrometheusClient,
    private val systemPrompt: String = SYSTEM,
) {
    private val log = LoggerFactory.getLogger(ContradictionDetector::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Pair(
        val from: Int,
        val to: Int,
    )

    suspend fun detect(resolved: List<ResolvedPage>): List<EdgeDraft> {
        if (resolved.size < 2) return emptyList()
        val pages = resolved.map { it.draft }
        val validLocalIds = pages.map { it.localId }.toSet()
        val userPrompt =
            pages.joinToString("\n\n") { "page ${it.localId} (${it.kind}): ${it.title}\n${it.contentMd.take(500)}" }
        val raw =
            try {
                prometheus.complete(systemPrompt, userPrompt)
            } catch (e: Exception) {
                log.warn("contradiction detection failed (no edges): {}", e.message)
                return emptyList()
            }
        return try {
            val pairs = json.decodeFromString<List<Pair>>(raw.trim())
            // Drop hallucinated endpoints: an edge is kept only if BOTH localIds
            // exist in this page set and it is not a self-edge. Otherwise the LoadApi
            // would write a CONTRADICTS edge to a non-existent node (a dangling edge
            // the graph walk later follows into nothing).
            val (valid, dangling) =
                pairs.partition { it.from != it.to && it.from in validLocalIds && it.to in validLocalIds }
            if (dangling.isNotEmpty()) {
                log.warn("dropping {} contradiction edge(s) with unknown/self localIds: {}", dangling.size, dangling)
            }
            valid.map { EdgeDraft(it.from, it.to, EdgeKind.CONTRADICTS) }
        } catch (e: Exception) {
            log.warn("contradiction output parse failed (no edges): {}", e.message)
            emptyList()
        }
    }

    companion object {
        val SYSTEM =
            """
            You compare wiki pages and flag CONTRADICTIONS — pairs that assert
            conflicting facts about the same entity/concept. Return ONLY a JSON
            array of {"from": <localId>, "to": <localId>} pairs; [] if none.
            """.trimIndent()
    }
}
