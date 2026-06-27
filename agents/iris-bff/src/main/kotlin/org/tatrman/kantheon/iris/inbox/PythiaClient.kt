package org.tatrman.kantheon.iris.inbox

/**
 * Read access to Pythia's persisted investigation state (contracts §2.7; Pythia
 * `GET /v1/investigations?user_id=…`). The inbox is a **view** over this — iris
 * keeps no investigation store. At Phase 4 the implementation is a fake /
 * Wiremock (Pythia `pythia/v0.1.0` lands the live HTTP client + the
 * `/events?from_seq=N` reattach bridge in the Pythia arc). The caller's OBO
 * bearer is forwarded (identity discipline — never service identity).
 */
interface PythiaClient {
    suspend fun listInvestigations(
        userId: String,
        bearer: String,
    ): List<InvestigationSummary>

    /**
     * Submit an investigation to Pythia (`POST /v1/investigations`) on the user's OBO
     * bearer; returns the assigned investigation id (Stage 5.2 T1). The SSE-bridge
     * consumption (`GET …/events?from_seq`) + control proxies are driven by the inbox
     * lifecycle infra + [PythiaEventMapper]; the live HTTP transport is in
     * [LivePythiaClient] (integration-deferred), the unit gate runs the fake.
     */
    suspend fun submit(
        questionJson: String,
        bearer: String,
    ): String
}

/** In-memory [PythiaClient] — the unit/component-test fake + local default
 *  (no live Pythia at Phase 4). Seed per-user investigation summaries. */
class FakePythiaClient(
    private val byUser: Map<String, List<InvestigationSummary>> = emptyMap(),
    private val submitId: String = "inv-fake-1",
) : PythiaClient {
    val submitted = mutableListOf<String>()

    override suspend fun listInvestigations(
        userId: String,
        bearer: String,
    ): List<InvestigationSummary> = byUser[userId].orEmpty()

    override suspend fun submit(
        questionJson: String,
        bearer: String,
    ): String {
        submitted += questionJson
        return submitId
    }
}
