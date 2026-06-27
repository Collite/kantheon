# Iris Phase 4 — inbox, artifacts, discovery, feedback (stage overview)

> **Entry point for the phase.** Structures the three Stage task lists and tracks aggregate progress. Added 2026-06-12 by the cohesion review — the PD-2 / PD-6 / PD-7 / PD-3 product surfaces, plus audit completion (PD-8).
>
> **Phase goal (plan §5a).** Investigation inbox + NATS lifecycle stream + hypothesis-tree pane (PD-2); pins & dashboards over `iris_artifacts` (PD-6); `/v1/discover` (PD-7); turn feedback + offline export (PD-3); audit verify endpoint + retention (PD-8). **Deployable at phase close: the product surfaces live; audit complete. Midas Stage 3.5 unblocked.**
>
> **Companions.** [`plan.md`](./plan.md) §5a · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §3.1 (inbox/artifacts/feedback packages), §5 (NATS/Pythia/Ed25519 runtime deps), §10.1 (Phase-4 metrics) · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.6 (discover), §2.7 (inbox), §2.8 (artifacts), §2.9 (feedback), §3.1 (audit), §3.2 (feedback table), §3.3 (artifacts table) · [`../../../architecture/kantheon-security.md`](../../../architecture/kantheon-security.md) §3 (visibility roles), §4 (audit chain).

## Pre-flight for the whole phase

- [ ] **Phase 3 closed** — `iris-bff/v0.3.0` + `iris/v0.2.0` (Themis routing, typed-action surface incl. `reask_agent` writing `corrected_agent_id`, `InvestigateChip` escalation).
- [ ] **`deployment/local` infra stage** has provisioned **NATS JetStream** reachable from the BFF (`iris.nats.*`) — required by Stage 4.1 only; inbox degrades to polling when down.
- [ ] **Stage 4.1 only:** Pythia `GET /v1/investigations?user_id=…` + the `pythia.lifecycle.{user_id}` NATS subject live (**Pythia Phase 1, `pythia/v0.1.0`**) + `iris.pythia.*` config. **Stages 4.2 / 4.3 have no Pythia dependency and may run earlier if Pythia slips** (plan §1 critical-path note).
- [ ] `iris_audit` + `iris_feedback` tables exist (Phase 1 D6 migration); Stage 4.2 adds `iris_artifacts` (contracts §3.3).
- [ ] Branch namespace `feat/iris-p4-s4.x-<short>`.

## Testing policy (planning-conventions §4)

Mocked unit/component only. **NATS** is faked (in-memory subject publisher / embedded test server stub) or Wiremock for the polling-fallback REST; **Pythia** is Wiremock/Fake; the **Ed25519** chain uses an ephemeral test keypair. Real-NATS reattach, live Pythia replay-then-live, and in-cluster acceptance → integration suite.

## Stages

| Stage | File | Goal | Tasks | Pythia dep? |
|---|---|---|---|---|
| **4.1** | [`tasks-p4-s4.1-inbox.md`](./tasks-p4-s4.1-inbox.md) | investigation inbox + NATS lifecycle subscriber + `/v1/inbox(/stream)` + FE panel + hypothesis-tree pane | 6 | **yes** (Pythia P1) |
| **4.2** | [`tasks-p4-s4.2-artifacts.md`](./tasks-p4-s4.2-artifacts.md) | `iris_artifacts` + pins & dashboards + refresh + FE + audit/metrics | 6 | no (Golem-kind pins) |
| **4.3** | [`tasks-p4-s4.3-discovery-feedback.md`](./tasks-p4-s4.3-discovery-feedback.md) | `/v1/discover` + turn feedback + `just feedback-export` + audit verify/retention + tags | 6 | no |

## Aggregate progress

- [x] **Stage 4.1** — inbox + lifecycle stream + hypothesis tree. **CODE-COMPLETE 2026-06-24 (fake-backed):** InboxAggregator (12→5 status, session/turn/origin join, counts), PythiaClient iface + FakePythiaClient, LifecycleHub fan-out + PollingLifecycleDriver fallback + `iris_lifecycle_nats_connected` gauge, `GET /v1/inbox(/stream)`; FE InboxBadge + InboxPanel (dockview) + HypothesisTree. **Live Pythia/NATS (list, `/events?from_seq=N` reattach, control proxy, live hypotheses) → Pythia arc** (`pythia/v0.1.0`); needs-input control proxy (T5) deferred with it.
- [x] **Stage 4.2** — artifacts: pins & dashboards. **DONE 2026-06-24:** ArtifactStore (+Exposed) + ArtifactService (capture/refresh/dashboard) + RoutingArtifactExecutor; `/v1/artifacts` CRUD + refresh + `/v1/dashboards/{id}/open` SSE; FE pin button + PinTile + ArtifactsPanel. Pin → dashboard → reopen → refresh round-trip green; **Midas Stage 3.5 consumable**. (Pythia-kind replay deferred to the Pythia arc.)
- [x] **Stage 4.3** — discovery + feedback + audit completion. **DONE 2026-06-24:** `GET /v1/discover` (DiscoverService, role-filtered), `POST /v1/turns/{id}/feedback` (FeedbackStore.upsertVerdict), `just feedback-export` (FeedbackExporter+CLI), `GET /v1/audit/verify?segment=` (verifySegment, admin-gated); FE DiscoverPanel + FeedbackButtons. **Tags `iris-bff/v0.4.0` + `iris/v0.3.0` pending PR merge.**

## Dependency notes carried from the plan

- Stage 4.3's feedback export consumes `iris_feedback.corrected_agent_id` written by Phase 3 Stage 3.2's `reask_agent`.
- Stage 4.3's audit completion sees Stage 4.2's `artifact_refresh` events — schedule 4.3 after 4.2 where audit-of-artifacts coverage is wanted (plan §5a Stage 4.3 dependencies).
- Stage 4.2 supersedes the Midas-arc dashboard design (contracts §2.8 Midas reframe): Midas Phase 3 Stage 3.5 becomes a *consumer* (domain templates + Golem-Investment content), not a builder.

---

*Overview owner: Bora. Written 2026-06-23 as part of the Iris Phase 3/4 task-list generation.*
