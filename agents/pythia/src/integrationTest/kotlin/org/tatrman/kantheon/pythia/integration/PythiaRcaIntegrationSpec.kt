package org.tatrman.kantheon.pythia.integration

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.testkit.integration.RequiresContext
import org.tatrman.kantheon.testkit.integration.RequiresContextExtension
import org.tatrman.kantheon.testkit.integration.contextHandle

/**
 * WS-C2 T5 — the **`pythia-rca`** context. The autonomous investigator's REST control surface
 * through a **real** Pythia pod. Gated by `@RequiresContext("pythia-rca")` — compiles + skips until
 * olymp stands the context up.
 *
 * ## Two fidelity tiers (mirrors golem-erp)
 * Pythia's edge is **async** (contracts §2): `POST /v1/investigations` → 202 `{id, status}`, then
 * `GET /v1/investigations/{id}` for the artifact — there is no synchronous run-to-terminal edge.
 * Every endpoint enforces **PD-8 admission** (auth/Admission.kt). That splits the tiers cleanly:
 *
 *  - **`contextLive` (ACTIVE)** — PD-8 admission on the submit→get flow, before any planning/LLM/
 *    query work. A **missing bearer** is rejected (403 `unauthenticated`); an investigation submitted
 *    by one user is **not visible to another** (403 `forbidden`) while its **owner can read it**
 *    (200). This proves the whole edge is live: Pythia boots (only DB-migration is fail-fast — this
 *    context runs `PYTHIA_DB_ENABLED=false`, in-memory, so it boots standalone with every downstream
 *    URL blank), accepts an async submit, persists the record, and enforces ownership. Robust; no
 *    LLM, no query, no other service. This is the deliverable that lands green first — the first
 *    end-to-end proof Pythia runs in-cluster.
 *
 *  - **`investigationLive` (GATED)** — the full investigation loop to a terminal Status
 *    (DONE/FAILED/HALTED/INCONCLUSIVE): submit with a non-interactive HitlPolicy, then poll. Reaching
 *    a *confident* DONE needs the SQL-only RCA chain — the LLM gateway (LLM: planner is STRONG, synth
 *    STRONG, eval/render CHEAP — no graceful degradation, so the stubs must be scripted) + Themis
 *    (resolve) + query-mcp (QueryNodes). This is the pythia arc's own "live-LLM RCA run" (plan.md
 *    Stage 3.3 T4, deferred here). Author the scripted WireMock stubs + expand the context to the
 *    chain, then flip this on.
 *
 * ## Context requirements (olymp `test-contexts/pythia-rca/context.yaml`)
 *  - Robust tier: **pythia alone** (`PYTHIA_DB_ENABLED=false`; all downstream URLs blank → stubs).
 *  - `readiness`: the kantheon gate derives readiness from the namespace (pythia Deployment
 *    Available; pythia `/ready` is UP once wired, dbReady=true even in-memory).
 */
@RequiresContext("pythia-rca")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class PythiaRcaIntegrationSpec :
    StringSpec({

        // ── Fidelity gates (WS-C2 T5) ────────────────────────────────────────────────────────
        // contextLive: the edge is up and PD-8 admission holds (robust, LLM-free).
        // investigationLive: the full loop reaches a terminal Status (needs the RCA chain + stubs).
        val contextLive = true
        // Flip ON once the context is expanded to the SQL-only RCA chain (llm-gateway+themis+query)
        // and the scripted LLM WireMock stubs are authored (planner/synth/eval). See plan.md S3.3 T4.
        val investigationLive = false

        // ── ROBUST: PD-8 admission ────────────────────────────────────────────────────────────
        // A missing bearer fails closed at the /v1 edge, before any orchestration.
        "a missing bearer is rejected with unauthenticated (PD-8)"
            .config(enabled = contextLive) {
                val handle = contextHandle()

                val res =
                    runBlocking {
                        handle.submitInvestigation(minimalRcaInvestigation("Why is revenue down?"), token = null)
                    }

                withClue({ "submit(no-bearer): status=${res.status} body=${res.body}" }) {
                    res.status shouldBe 403
                    res.firstMessageCode() shouldBe "unauthenticated"
                }
            }

        // PD-8 ownership visibility: a submitted investigation is visible to its owner (200) but not
        // to another non-admin user (403). This also proves the async submit persisted a record.
        "an investigation is visible to its owner but forbidden to another user (PD-8)"
            .config(enabled = contextLive) {
                val handle = contextHandle()
                val alice = pythiaToken("alice")
                val bob = pythiaToken("bob")

                val submit =
                    runBlocking {
                        handle.submitInvestigation(minimalRcaInvestigation("Why did margins slip in Q2?"), alice)
                    }
                withClue({ "submit(alice): status=${submit.status} body=${submit.body}" }) {
                    submit.status shouldBe 202
                }
                val id = submit.investigationId().shouldNotBeNull()

                val asBob = runBlocking { handle.getInvestigation(id, bob) }
                withClue({ "get(bob): status=${asBob.status} body=${asBob.body}" }) {
                    asBob.status shouldBe 403
                    asBob.firstMessageCode() shouldBe "forbidden"
                }

                val asAlice = runBlocking { handle.getInvestigation(id, alice) }
                withClue({ "get(alice): status=${asAlice.status} body=${asAlice.body}" }) {
                    asAlice.status shouldBe 200
                }
            }

        // ── GATED: full investigation loop ───────────────────────────────────────────────────
        // A minimal RCA investigation runs to a terminal Status. GATED until the RCA chain is stood
        // up and the scripted LLM stubs are authored (Pythia's LLM legs don't degrade gracefully).
        "a minimal RCA investigation reaches a terminal status"
            .config(enabled = investigationLive) {
                val handle = contextHandle()
                val alice = pythiaToken("alice")

                val submit =
                    runBlocking {
                        handle.submitInvestigation(minimalRcaInvestigation("Why did revenue drop last month?"), alice)
                    }
                submit.status shouldBe 202
                val id = submit.investigationId().shouldNotBeNull()

                val terminal = runBlocking { handle.pollInvestigationUntilTerminal(id, alice) }
                withClue(
                    {
                        "terminal: httpStatus=${terminal.status} status=${terminal.statusField()} body=${terminal.body}"
                    },
                ) {
                    terminal.status shouldBe 200
                    terminal.statusField().shouldNotBeNull() shouldBeIn PYTHIA_TERMINAL_STATUSES
                }
            }
    })
