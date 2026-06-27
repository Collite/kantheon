package org.tatrman.kantheon.kleio

import org.slf4j.LoggerFactory
import org.tatrman.kantheon.capabilities.client.CapabilitiesClient
import org.tatrman.kantheon.capabilities.v1.AgentCapability
import org.tatrman.kantheon.capabilities.v1.AgentKind
import org.tatrman.kantheon.capabilities.v1.Capability
import org.tatrman.kantheon.capabilities.v1.IntentKind

/**
 * Kleio's `AgentCapability` (contracts §9): `KNOWLEDGE_QA`, intent `[KNOWLEDGE]`,
 * routable. The router copy + few-shots steer Themis Layer 2; the
 * counter-examples keep data/procedure questions OFF Kleio (→ Golem/Pythia). The
 * `capability_refs` are the `library.*` tools Kleio consumes.
 */
object KleioRegistration {
    private val log = LoggerFactory.getLogger(KleioRegistration::class.java)

    fun capability(serviceEndpoint: String): Capability =
        Capability
            .newBuilder()
            .setAgent(
                AgentCapability
                    .newBuilder()
                    .setAgentKind(AgentKind.KNOWLEDGE_QA)
                    .setAgentId("kleio")
                    .setDisplayName("Kleio — the Librarian (NotebookLM)")
                    .addIntentKindsSupported(IntentKind.KNOWLEDGE)
                    .setDescriptionForRouter(
                        "Answers questions about documents in a notebook (mart) — contracts, reports, wikis, " +
                            "policies — with grounded, cited answers and artifacts (summary/FAQ/timeline/briefing). " +
                            "NOT for live data numbers (that is Golem) or multi-step investigations (that is Pythia).",
                    ).addAllExampleQuestions(
                        listOf(
                            "What does the supplier contract say about termination?",
                            "Summarise the Q3 board pack.",
                            "What are the open risks mentioned across these reports?",
                            "Give me an FAQ for the onboarding handbook.",
                        ),
                    ).addAllCounterExamples(
                        listOf(
                            "What was revenue last quarter?", // data → Golem
                            "Why did churn spike in March?", // investigation → Pythia
                            "Forecast next quarter's sales.", // forecast → Pythia
                        ),
                    ).addAllCapabilityRefs(
                        listOf(
                            "library.getContext:v1",
                            "library.search:v1",
                            "library.getPage:v1",
                            "library.traverse:v1",
                        ),
                    ).setServiceEndpoint(serviceEndpoint)
                    .setHealthCheckPath("/health")
                    .setTypicalLatencyMs(1500)
                    .setNonRoutable(false)
                    .build(),
            ).build()

    fun register(
        endpoint: String,
        serviceEndpoint: String,
    ) {
        if (endpoint.isBlank()) {
            log.info(
                "CAPABILITIES_MCP_URL not set — Kleio AgentCapability not registered (router won't route KNOWLEDGE until set).",
            )
            return
        }
        CapabilitiesClient.startupRegister(
            capability = capability(serviceEndpoint),
            endpoint = endpoint,
            heartbeatIntervalMs = 30_000,
        )
        log.info("Kleio registered KNOWLEDGE_QA with capabilities-mcp at {}", endpoint)
    }
}
