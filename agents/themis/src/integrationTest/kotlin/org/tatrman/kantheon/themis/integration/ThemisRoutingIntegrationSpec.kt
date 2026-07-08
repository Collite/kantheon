package org.tatrman.kantheon.themis.integration

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.testkit.integration.RequiresContext
import org.tatrman.kantheon.testkit.integration.RequiresContextExtension
import org.tatrman.kantheon.testkit.integration.contextHandle

/**
 * WS-C2 T5 — the **`themis-routing`** context. Question understanding + agent routing through a
 * **real** themis-mcp pod wired to real Kadmos (NLP) + Echo (fuzzy) + capabilities-mcp (agent
 * manifests), with the LLM upstream (via Prometheus) available at WireMock. Gated by
 * `@RequiresContext("themis-routing")` — compiles + skips until olymp stands the context up.
 *
 * ## Two fidelity tiers (mirrors golem-erp)
 * Themis has two edges (Main.kt) with different capabilities, so the tiers split cleanly:
 *
 *  - **`contextLive` (ACTIVE)** — the MCP `resolve` tool over StreamableHTTP. On this edge Themis
 *    sets `routingEnabled=false`, so it runs NLP (Kadmos) + the resolution graph and returns a
 *    terminal outcome, but computes no routing. This is a **deterministic resolve smoke via graceful
 *    LLM degradation**: every LLM-calling node (`classify`/`filter`/`joint`/`route`) degrades with a
 *    `.getOrElse` fallback, so even with no WireMock LLM stub the graph terminates in a well-formed
 *    `resolved` / `awaiting_clarification` / `refused` outcome — never a crash. The smoke proves the
 *    whole chain is live (themis boots past its `assertRoutableAgentsAvailable` gate against
 *    capabilities-mcp, reaches Kadmos, runs the graph). This is the deliverable that lands green
 *    first. No bearer required (the MCP edge has no identity check).
 *
 *  - **`routingLive` (GATED)** — the REST `POST /v1/resolve` edge, the ONLY edge that computes
 *    routing (`Resolution.routing`). Asserts a procedural question routes to a concrete agent
 *    (`chosen_agent_id`). Reaching a *routed* Resolution (not a clarification) needs the LLM legs to
 *    actually resolve — `joint` (sonnet) + `route` (haiku) — so it depends on deterministic WireMock
 *    stubs shaped to each leg's decode contract. Author those from the first bp-dsk run's Prometheus
 *    logs (the golem-erp method), then flip this on. It also first-exercises the server-side
 *    proto-canonical-JSON receive of the REST edge end-to-end.
 *
 * ## Context requirements (reconciled with olymp `test-contexts/themis-routing/context.yaml`)
 *  - Services (real): themis-mcp, capabilities-mcp (seed agent manifests → themis boots), kadmos
 *    (NLP), echo (fuzzy), prometheus (Spring `test` profile → H2, LLM base-url'd at WireMock).
 *  - Platform: `wiremock` (LLM upstream stub — empty on the robust tier; the graph degrades).
 *  - No mssql / theseus chain — routing classifies the question; it does not execute a query.
 *  - `readiness`: the kantheon gate derives readiness from the namespace (every Deployment
 *    Available) — no handshake annotation.
 */
@RequiresContext("themis-routing")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class ThemisRoutingIntegrationSpec :
    StringSpec({

        // ── Fidelity gates (WS-C2 T5) ────────────────────────────────────────────────────────
        // contextLive: the context stands up and the MCP resolve graph runs (robust smoke).
        // routingLive: the REST routing edge yields a concrete chosen_agent_id (needs LLM stubs).
        val contextLive = true
        // Flip ON after the first bp-dsk run confirms the routing LLM legs + the REST proto-JSON
        // receive. Enabling requires deterministic WireMock stubs for joint (sonnet) + route (haiku).
        val routingLive = false

        val terminalOutcomes = listOf("resolved", "awaiting_clarification", "refused")

        // ── ROBUST: MCP resolve smoke ────────────────────────────────────────────────────────
        // The chain is live and the graph terminates in a well-formed outcome. `isError=false`
        // proves no node threw AND that Kadmos NLP ran — the graph builds its context from the NLP
        // response before any node, so an NLP failure throws and surfaces as `isError=true` with no
        // `outcome`. So a non-error result carrying a recognized `outcome` is the end-to-end proof
        // Themis runs in-cluster: boots past the capabilities-mcp routable-agents gate, reaches
        // Kadmos, and executes the resolution graph (degrading gracefully on the stub-less LLM legs).
        //
        // NOTE: we do NOT assert `trace_id` — Themis's MCP resolve only emits it on the `resolved`
        // and `refused` branches, not `awaiting_clarification` (Main.kt), which is the expected
        // degraded outcome with an empty WireMock. `isError=false` already covers "Kadmos ran".
        "resolve returns a well-formed terminal outcome"
            .config(enabled = contextLive) {
                val handle = contextHandle()

                // Retry across a cold Kadmos: the first attempt triggers its lazy model-load (paired
                // with the raised NLP timeout in themis-mcp.values.yaml + more Kadmos memory); if that
                // load restarts the pod, a follow-up connect can be briefly refused, so a later
                // attempt hits the recovered pod. The NLP/fuzzy legs don't degrade gracefully, so a
                // blip surfaces as isError — resolveUntilOk returns the first non-error result.
                val res = runBlocking { handle.resolveUntilOk("Kolik máme objednávek za minulý měsíc?") }

                // Surface the whole result on failure — a hard error names exactly which leg broke.
                withClue({ "resolve: isError=${res.isError} body=${res.bodyText()}" }) {
                    res.isError shouldBe false
                    res.outcome().shouldNotBeNull() shouldBeIn terminalOutcomes
                }
            }

        // ── GATED: REST agent-routing ────────────────────────────────────────────────────────
        // A procedural ERP question resolves to a concrete downstream agent. Routing is computed
        // ONLY on the REST edge (the MCP edge disables it). GATED until deterministic LLM stubs make
        // the joint/route legs resolve rather than degrade to a clarification.
        "a procedural question routes to a concrete agent via REST /v1/resolve"
            .config(enabled = routingLive) {
                val handle = contextHandle()
                val bearer = unsignedJwt("alice", roles = listOf("kantheon-area-accounting"))

                val response =
                    runBlocking {
                        handle.resolveRest(
                            freshResolveRequest("Kolik máme objednávek za minulý měsíc?", locale = "cs"),
                            bearer,
                        )
                    }

                withClue({ "resolve response: $response" }) {
                    response.hasResolution() shouldBe true
                    response.resolution.hasRouting() shouldBe true
                    response.resolution.routing.chosenAgentId.value
                        .shouldNotBeBlank()
                }
            }
    })
