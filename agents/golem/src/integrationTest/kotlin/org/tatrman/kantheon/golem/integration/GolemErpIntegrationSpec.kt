package org.tatrman.kantheon.golem.integration

import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.testkit.integration.RequiresContext
import org.tatrman.kantheon.testkit.integration.RequiresContextExtension
import org.tatrman.kantheon.testkit.integration.contextHandle

/**
 * WS-C2 T4 — the **`golem-erp`** agent-showcase context. A domain Q&A turn through a **real**
 * Golem-ERP pod: Golem `/v1/answer/sync` → `ShemAdmission` (PD-8) → Ariadne (model via `GetModel`,
 * the bundled `accounting` area) → `PlanComposer` (LLM, WireMock-stubbed via Prometheus) →
 * `MiniPlanExecutor` → an `envelope/v1` `ConversationalResponse`. Gated by
 * `@RequiresContext("golem-erp")` — compiles + skips until olymp stands the context up.
 *
 * ## Fixture agent-showcase (Bora, 2026-07-08)
 * This context proves the **Golem agent turn**, NOT real query data — the `tpcds-query` showcase
 * owns the real-data proof. The query leg is fixture-only (`theseus-runquery` returns
 * `detection_failed`, not rows — Proteus' fixture model has no seed table). So there are two
 * fidelity tiers, gated separately:
 *
 *  - **`contextLive` (ACTIVE)** — PD-8 Shem admission at the `/v1` edge, before any model/LLM/query
 *    work: a **missing bearer** fails closed (401), an **outsider role** is forbidden (403). Needs
 *    only golem (with the `golem-erp` Shem) + ariadne (Shem's `accounting` area → model load →
 *    Ready). Robust; no LLM, no query. This is the deploy-test deliverable that lands green first.
 *
 *  - **`answerTurnLive` (GATED)** — the LLM-planned agent turn: admission → `PlanComposer`
 *    (Prometheus → WireMock stub) → `MiniPlanExecutor` renders a **RENDER-ONLY** MiniPlan (a single
 *    render node, no query node → an empty TABLE envelope) → `STATUS_DONE`. The rendered table is
 *    **data-less** by design (real rows are the `tpcds-query` path). Flip once the first bp-dsk run
 *    confirms the golem→prometheus→WireMock LLM roundtrip (Spring AI 2.0.0-M2 Anthropic wire shape).
 *
 * ## Context requirements (reconciled with olymp `test-contexts/golem-erp/context.yaml`)
 *  - Services (real): golem (loaded with the `golem-erp` Shem via ConfigMap), ariadne (bundled
 *    `accounting` model — no image change), prometheus (Spring `test` profile → H2, LLM base-url'd
 *    at WireMock). Platform: `wiremock` (LLM upstream stub). No theseus chain / mssql — the
 *    render-only turn never queries.
 *  - Identity: golem `/v1` enforces PD-8 admission — the bearer's `realm_access.roles` must
 *    intersect the Shem's `visibility_roles` (`kantheon-area-accounting`); a missing bearer fails
 *    closed. These are the ACTIVE assertions.
 *  - `readiness`: the kantheon gate derives readiness from the namespace (every Deployment
 *    Available) — no handshake annotation.
 */
@RequiresContext("golem-erp")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class GolemErpIntegrationSpec :
    StringSpec({

        // ── Fidelity gates (WS-C2 T4) ────────────────────────────────────────────────────────
        // contextLive: the context stands up (golem+ariadne+prometheus+wiremock) and PD-8 admission
        // is exercised. answerTurnLive: the LLM-planned render turn (needs the live golem→prometheus
        // →WireMock roundtrip confirmed). Flip answerTurnLive after the first bp-dsk run.
        val contextLive = true
        val answerTurnLive = false

        // The Shem's visibility role — matches the deployed golem-erp Shem manifest's
        // `visibility_roles` (`agents/golem/shems/golem-erp/shem.yaml`).
        // Convention `kantheon-area-<area>` (area = accounting).
        val erpRole = "kantheon-area-accounting"

        // T3 — OBO/admission discipline: a missing bearer fails closed at the /v1 edge, before any
        // model/LLM/query work. (Token `exp` is enforced here, JWKS at ingress — kantheon-security.md.)
        // ACTIVE once the context stands up (needs the Shem loaded so the /v1/answer route exists).
        "a missing OBO bearer fails closed with unauthorized"
            .config(enabled = contextLive) {
                val handle = contextHandle()

                val answer =
                    runBlocking {
                        handle.answerGolem(
                            golemRequest(
                                id = "it-golem-erp-noauth",
                                golemId = "golem-erp",
                                question = "How many orders?",
                            ),
                            bearer = null,
                        )
                    }

                answer.status shouldBe 401
                answer.firstMessageCode() shouldBe "unauthorized"
            }

        // T2/T4 — role-visibility (PD-8): a caller whose roles don't intersect the Shem's
        // visibility_roles is forbidden, never reaching the domain model/LLM. ACTIVE (admission-only).
        "a caller outside the Shem visibility roles is forbidden"
            .config(enabled = contextLive) {
                val handle = contextHandle()
                val outsider = unsignedJwt("mallory", roles = listOf("unrelated_role"))

                val answer =
                    runBlocking {
                        handle.answerGolem(
                            golemRequest(
                                id = "it-golem-erp-forbidden",
                                golemId = "golem-erp",
                                question = "How many orders?",
                            ),
                            outsider,
                        )
                    }

                answer.status shouldBe 403
                answer.firstMessageCode() shouldBe "forbidden"
            }

        // T1 — the LLM-planned agent turn: an admitted domain question yields a DONE turn with a
        // rendered envelope. The WireMock-stubbed MiniPlan is render-only, so the turn completes
        // without the query chain (the envelope is a data-less TABLE). GATED (answerTurnLive) until
        // the live golem→prometheus→WireMock roundtrip is confirmed on bp-dsk.
        "an admitted domain question returns a STATUS_DONE turn with a rendered envelope"
            .config(enabled = answerTurnLive) {
                val handle = contextHandle()
                val bearer = unsignedJwt("alice", roles = listOf(erpRole))

                val answer =
                    runBlocking {
                        handle.answerGolem(
                            golemRequest(
                                id = "it-golem-erp-1",
                                golemId = "golem-erp",
                                question = "How many orders are there?",
                            ),
                            bearer,
                        )
                    }

                answer.status shouldBe 200
                answer.turnStatus() shouldBe "STATUS_DONE"
                answer.envelopes().shouldNotBeEmpty()
            }
    })
