package org.tatrman.kantheon.iris.dispatch

/**
 * Parse the `iris.dispatch.agent-endpoints` spec — `id=url,id=url` — into an agent-id → /v2
 * endpoint map.
 *
 * Themis routes to the agent id **as registered in capabilities-mcp** (`golem-hartland`,
 * `golem-hartland-finance`, …), while the dispatch map is otherwise keyed only on the Phase-3
 * placeholder `golem-v2`. A correct routing decision therefore died at [AgentDispatcher] with
 * "No dispatch client registered for agent '<id>'" — routing and dispatch never agreed on the
 * namespace of an agent id. A multi-golem estate needs the map regardless: one base-url cannot
 * address two golems on separate Services.
 *
 * Malformed entries are skipped rather than fatal: this is deployment config, and a stray comma
 * should not take the BFF down. It is a plain map, so a repeated id keeps the last endpoint.
 */
fun parseAgentEndpoints(spec: String): Map<String, String> =
    spec
        .split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2).map(String::trim)
            if (parts.size != 2) return@mapNotNull null
            val (id, url) = parts
            if (id.isEmpty() || url.isEmpty()) null else id to url
        }.toMap()
