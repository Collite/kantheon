# Iris Phase 3 — routing UX + typed actions + observability (stage overview)

> **Entry point for the phase.** This document structures the three Stage task lists and tracks aggregate progress. Pick up the work here.
>
> **Phase goal (plan §5).** The BFF calls `themis.understand()` per turn; the `RoutingPickChip` round-trip is live; the full typed-action surface (incl. `reask_agent`) + entity context + `HandoffContext` assembly + `InvestigateChip` land on the BFF; a Grafana dashboard ships. **Deployable at phase close: routing UX shipped.**
>
> **Companions.** [`plan.md`](./plan.md) §5 · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §6.2 (turn dispatch transitional→target), §10 (observability) · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §1.2 (iris/v1), §2.2–2.5 (chat/action surface) · [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §ResolveRequest/ResolveResponse/RoutingDecision · [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) §2–3 (OBO + routing-view rules).

## Pre-flight for the whole phase

- [ ] **Phase 2 closed** — `iris/v0.1.0` + `iris-bff/v0.2.0` (the FE talks only to the BFF; `/v1/action` route skeleton + `edit_resend` exist; `typedAction.ts` FE stub in place).
- [ ] **`themis/v0.2.0` closed OR co-developed.** Themis Phase 3 Stage 3.6 ("Iris BFF stub") tasks are executed *here* in `agents/iris-bff` against Themis's fixture LLM — one codebase, no throwaway stub. The Themis arc plan points at this overview; coordinate the shared fixture corpus before starting Stage 3.1.
- [ ] **capabilities-mcp live** with `pythia` + `golem-erp` fixtures (display names, `visibility_roles`, `description_for_router`, `example_questions`, `alternates`). Consumed via `shared/libs/kotlin/capabilities-client` (`CapabilitiesReadClient`).
- [ ] **`shared/proto`** carries the Phase-3 themis/v1 additions (`Profile`, `RoutingDecision`, `AgentAlternate`, `MultiQuestionDetected`, `RefusalWithGaps`, `routing_hint`, `prior_context`) — generated KT bindings available to the BFF.
- [ ] Branch namespace `feat/iris-p3-s3.x-<short>` per planning-conventions §2.

## Testing policy (planning-conventions §4)

Mocked unit/component tests only. Themis is mocked via **Wiremock** (HTTP) or a `FakeThemisClient`; capabilities-mcp via a fake read client; new-golem `/v2` via the existing `FakeGolemV2Client` / Wiremock. The chip round-trip against the *real* Themis fixture LLM and any in-cluster acceptance are recorded for the separate integration suite — **not** stage blockers. (The shared-fixture chip round-trip is the one integration item co-owned with Themis Stage 3.6.)

## Stages

| Stage | File | Goal | Tasks |
|---|---|---|---|
| **3.1** | [`tasks-p3-s3.1-themis-routing.md`](./tasks-p3-s3.1-themis-routing.md) | `themis.understand()` per turn; HandoffContext assembly; AgentDispatcher; RoutingPickChip round-trip; RefusalWithGaps + PD-13 decomposition | 7 |
| **3.2** | [`tasks-p3-s3.2-typed-actions.md`](./tasks-p3-s3.2-typed-actions.md) | full `/v1/action` surface; static+dynamic chips; EntityContext/applied_context + PD-4 scope; `reask_agent` (PD-14); `InvestigateChip` (PD-1) | 8 |
| **3.3** | [`tasks-p3-s3.3-observability.md`](./tasks-p3-s3.3-observability.md) | metrics; Grafana dashboard; single-trace audit; load sanity; doc fold-ins; tags | 6 |

## Aggregate progress

- [x] **Stage 3.1** — Themis integration + routing dispatch (== Themis Stage 3.6 stub tasks 2–3). **Done 2026-06-23** — chip round-trip green against the fixture (Wiremock/Fake) Themis; live fixture-LLM round-trip → integration suite.
- [x] **Stage 3.2** — typed actions (incl. `reask_agent`) + chips + context + `InvestigateChip`. **COMPLETE: BFF 2026-06-23 (T1 shaping / T2 drilldown / T3 chips / T4 EntityContext+PD-4 / T6 reask_agent / T7 InvestigateChip); FE 2026-06-24 (T5 typedAction.ts + RoutingPickChip/ChipStrip/InvestigateChip + agent badge/re-ask picker + table sort/filter/paginate/drilldown rewire; T8 component pass — 215 FE tests).**
- [~] **Stage 3.3** — observability + docs. **BFF DONE 2026-06-23 (metrics + /metrics + Grafana + load sanity); single-trace OTel → kantheon#27; tags `iris-bff/v0.3.0` + `iris/v0.2.0` pending PR merge.**

## Dependency notes carried from the plan

- Stage 3.1 is the single implementation of Themis Stage 3.6's "Iris BFF stub" — closing 3.1 closes those Themis tasks too. Keep the two plans cross-referenced.
- Stage 3.2's `reask_agent` produces `iris_feedback.corrected_agent_id`, which Phase 4 Stage 4.3's feedback export consumes. The `iris_feedback` table already exists (Phase 1 D6 migration); 3.2 only writes the `corrected_agent_id` column.
- `InvestigateChip` (3.2 T7) renders + re-issues with `routing_hint=pythia`; the Pythia agent client that actually serves the investigation is a later arc — at Phase 3 the re-issue still routes through Themis/AgentDispatcher (transitional target = new-golem unless a native pythia client is wired).

---

*Overview owner: Bora. Written 2026-06-23 as part of the Iris Phase 3/4 task-list generation.*
