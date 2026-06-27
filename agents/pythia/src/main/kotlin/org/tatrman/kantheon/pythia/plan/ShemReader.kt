package org.tatrman.kantheon.pythia.plan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.slf4j.LoggerFactory

/** A relevant Golem's Shem context, injected into the planner prompt (no agent-to-agent calls — R4). */
data class ShemContext(
    val areaName: String,
    val areaTerminology: List<String>,
    val preferredQueries: List<String>,
    val preferredCapabilities: List<String>,
)

/** Source of agent manifests (capabilities-mcp `/v1/capabilities/agents`); a seam for testing. */
fun interface AgentManifestSource {
    suspend fun listAgents(): JsonObject
}

/**
 * Master-of-Golems (architecture §5, Stage 5.1 T1). For the resolved entities, find the
 * relevant Golems — **relevance = `area_entities` ∩ resolved entities** — and pull their
 * `preferred_queries` + `area_terminology` + `preferred_capabilities` (the ShemManifest,
 * golem/contracts.md §6) as **structured context** for the planner prompt. Pythia *reads*
 * manifests; it never calls a Golem (R4). Registry-unreachable degrades to no context.
 */
class ShemReader(
    private val source: AgentManifestSource,
) {
    private val log = LoggerFactory.getLogger(ShemReader::class.java)

    suspend fun relevantShems(resolvedEntities: Collection<String>): List<ShemContext> {
        if (resolvedEntities.isEmpty()) return emptyList()
        val entitySet = resolvedEntities.map { it.lowercase() }.toSet()
        val agents =
            runCatching { source.listAgents() }
                .onFailure {
                    log.warn(
                        "master-of-Golems: capabilities-mcp unreachable — no Shem context: {}",
                        it.message,
                    )
                }.getOrNull() ?: return emptyList()
        val list = (agents["agents"] as? JsonArray) ?: return emptyList()
        return list
            .filterIsInstance<JsonObject>()
            .filter { it.str("agent_kind").equals("AREA_QA", ignoreCase = true) }
            .filter { manifest -> manifest.strList("area_entities").any { it.lowercase() in entitySet } }
            .map { manifest ->
                ShemContext(
                    areaName = manifest.str("area_name") ?: manifest.str("agent_id") ?: "",
                    areaTerminology = terminology(manifest),
                    preferredQueries = manifest.strList("preferred_queries"),
                    preferredCapabilities = manifest.strList("capability_refs"),
                )
            }
    }

    /** Render the relevant Shems into a compact structured block for the planner prompt. */
    fun render(shems: List<ShemContext>): String {
        if (shems.isEmpty()) return ""
        return shems.joinToString("\n") { s ->
            buildString {
                append("[area ${s.areaName}]")
                if (s.areaTerminology.isNotEmpty()) append(" terminology: ${s.areaTerminology.joinToString("; ")}.")
                if (s.preferredQueries.isNotEmpty()) {
                    append(
                        " preferred queries: ${s.preferredQueries.joinToString(", ")}.",
                    )
                }
                if (s.preferredCapabilities.isNotEmpty()) {
                    append(" capabilities: ${s.preferredCapabilities.joinToString(", ")}.")
                }
            }
        }
    }

    private fun terminology(manifest: JsonObject): List<String> =
        (manifest["area_terminology"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { t ->
                val term = t.str("term") ?: return@mapNotNull null
                val def = t.str("definition") ?: ""
                "$term — $def"
            } ?: emptyList()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}
