package org.tatrman.kantheon.iris.discover

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A discovery card — "what can I ask about this domain?" (PD-7, contracts §2.6). */
data class DomainCard(
    val agentId: String,
    val displayName: String,
    val blurb: String,
    val exampleQuestions: List<String>,
)

/**
 * Builds the discovery surface from the capabilities-mcp agent manifests (PD-7,
 * contracts §2.6). Pure transform: `non_routable` entries are excluded, and an
 * agent whose `visibility_roles` are non-empty is shown only when the caller
 * holds an intersecting role (PD-7 — you only discover what you may ask). Agents
 * with no role restriction are public. Field access is defensive (camelCase
 * proto-JSON with snake_case + nested-`manifest` fallbacks), mirroring
 * `CapabilitiesAgentLabels`.
 */
object DiscoverService {
    fun build(
        agentsJson: JsonObject,
        callerRoles: Set<String>,
    ): List<DomainCard> {
        val agents = (agentsJson["agents"] as? JsonArray) ?: return emptyList()
        return agents.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            if (boolField(a, "nonRoutable", "non_routable") == true) return@mapNotNull null
            val visibility = listField(a, "visibilityRoles", "visibility_roles").toSet()
            if (visibility.isNotEmpty() && callerRoles.intersect(visibility).isEmpty()) return@mapNotNull null

            val agentId = strField(a, "agentId", "agent_id", "id") ?: return@mapNotNull null
            DomainCard(
                agentId = agentId,
                displayName = strField(a, "displayName", "display_name", "name") ?: agentId,
                blurb = strField(a, "descriptionForRouter", "description_for_router", "blurb") ?: "",
                exampleQuestions = listField(a, "exampleQuestions", "example_questions"),
            )
        }
    }

    // --- defensive field access (top-level, then a nested manifest object) ---

    private fun obj(a: JsonObject): List<JsonObject> =
        buildList {
            add(a)
            (a["manifest"] as? JsonObject)?.let { add(it) }
            (a["agentManifest"] as? JsonObject)?.let { add(it) }
        }

    private fun strField(
        a: JsonObject,
        vararg keys: String,
    ): String? =
        obj(a).firstNotNullOfOrNull { o ->
            keys.firstNotNullOfOrNull { k ->
                runCatching { o[k]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }

    private fun boolField(
        a: JsonObject,
        vararg keys: String,
    ): Boolean? =
        obj(a).firstNotNullOfOrNull { o ->
            keys.firstNotNullOfOrNull { k ->
                runCatching { (o[k] as? JsonPrimitive)?.booleanOrNull }.getOrNull()
            }
        }

    private fun listField(
        a: JsonObject,
        vararg keys: String,
    ): List<String> =
        obj(a).firstNotNullOfOrNull { o ->
            keys.firstNotNullOfOrNull { k ->
                (o[k] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }
        } ?: emptyList()
}
