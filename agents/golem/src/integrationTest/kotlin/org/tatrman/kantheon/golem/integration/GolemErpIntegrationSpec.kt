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
 * Stage 3.1 T1 — end-to-end domain Q&A turn through a **real** Golem-ERP pod and its
 * chain: Golem `/v1/answer/sync` → Ariadne (model via `GetModel`; prompts from the mounted Shem)
 * → PlanComposer (LLM, WireMock-stubbed) → the theseus query chain (theseus-mcp →
 * Proteus → Argos → Kyklop → Brontes → MSSQL) → an `envelope/v1` `ConversationalResponse`.
 * Gated by `@RequiresContext("golem-erp")` — compiles + skips until olymp stands the
 * context up, then runs green (the Stage 2.2 ahead-of-cluster pattern).
 *
 * ## Upstream readiness gate (testing arc Stage 3.1, Bora 2026-06-24)
 * The `golem-erp` chain is **not yet chart-complete**: Golem's hard model dependency
 * **Ariadne** (`GetModel`) and the **Prometheus** LLM gateway have no D3′ Helm chart
 * (only golem + the theseus query chain + brontes do). So the live context cannot stand
 * up yet, and the answer-turn assertions below are gated OFF behind `liveContext` —
 * exactly as `RunQueryIntegrationSpec.modelAlignedContext` does for theseus-runquery.
 * The ACTIVE assertion is the admission/identity discipline (missing bearer fails
 * closed), which needs none of those upstreams. Flip `liveContext` once Ariadne +
 * Prometheus reach a chart and olymp `test-contexts/golem-erp/` deploys the full chain.
 * Tracking: docs/implementation/v1/testing/plan.md Phase 3 + tasks-p3-s3.1-contexts.md T1.
 *
 * ## Context requirements (reconciled with olymp `test-contexts/golem-erp/context.yaml`)
 *  - Services (real): golem (loaded with the `golem-erp` Shem), ariadne, theseus, theseus-mcp,
 *    proteus, argos, kyklop, brontes. **(ariadne/prometheus charts pending — see gate above.)**
 *  - Platform: `mssql` (the `mssql-init` Job seeds the sample dataset) + `wiremock`
 *    (LLM-gateway upstream stub; fixtures at `.../wiremock/golem-erp/`).
 *  - Identity: golem `/v1` enforces PD-8 admission — the bearer's `realm_access.roles`
 *    must intersect the Shem's `visibility_roles`; a missing bearer is the ACTIVE
 *    fail-closed assertion below (`unauthorized`, HTTP 401).
 *  - `readiness`: the kantheon gate derives readiness from the namespace (every
 *    Deployment Available + `mssql-init` Complete) — no handshake annotation.
 */
@RequiresContext("golem-erp")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class GolemErpIntegrationSpec :
    StringSpec({

        // ── Upstream readiness gate (testing arc Stage 3.1) ─────────────────────────────────
        // OFF until ariadne + prometheus reach a D3′ chart and the golem-erp context can stand
        // the full chain up. The admission assertion below is GREEN without them.
        val liveContext = false

        // The Shem's visibility role — must match the deployed golem-erp Shem manifest's
        // `visibility_roles`. Aligned with the olymp context's seed when `liveContext` flips.
        // Convention `kantheon-area-<area>` (renamed from `kantheon-domain-<shem>` 2026-06-25).
        val erpRole = "kantheon-area-accounting"

        // T1 — happy path: a domain question yields a DONE turn with a rendered envelope.
        "a domain question returns a STATUS_DONE turn with a rendered envelope"
            .config(enabled = liveContext) {
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

        // T3 — OBO/admission discipline: a missing bearer fails closed at the /v1 edge,
        // before any model/LLM/query work. (Token `exp` is enforced here, JWKS at ingress —
        // see kantheon-security.md.) This is the ACTIVE assertion that runs once the context
        // is live, independent of the Ariadne/Prometheus chart gate.
        "a missing OBO bearer fails closed with unauthorized" {
            val handle = contextHandle()

            val answer =
                runBlocking {
                    handle.answerGolem(
                        golemRequest(id = "it-golem-erp-noauth", golemId = "golem-erp", question = "How many orders?"),
                        bearer = null,
                    )
                }

            answer.status shouldBe 401
            answer.firstMessageCode() shouldBe "unauthorized"
        }

        // T2/T4 — role-visibility: a caller whose roles don't intersect the Shem's
        // visibility_roles is forbidden (PD-8), never reaching the domain model.
        "a caller outside the Shem visibility roles is forbidden"
            .config(enabled = liveContext) {
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
    })
