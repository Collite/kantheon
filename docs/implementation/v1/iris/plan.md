# Iris — Phased Implementation Plan (kantheon arc)

> **Scope.** From "no Iris in kantheon" to "Iris SPA + BFF live in local K3s, serving daily use against the transitional new-golem backend, routing-UX ready for the constellation — plus the PD-2/PD-6/PD-7/PD-3/PD-8 product surfaces (inbox, artifacts, discovery, feedback, audit)." **Four phases, fourteen stages, ~85 tasks** (Phase 4 added 2026-06-12 by the cohesion review).
>
> **Companions.** [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md), [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`../../../design/iris/iris-design.md`](../../../design/iris/iris-design.md).
>
> **Arc ordering (locked 2026-06-12).** Iris → Golem → Pythia. Iris ships against new-golem `/v2` (transitional adapter); the Golem rewrite cuts over against a stable Iris.
>
> **Testing.** Per the testing policy ([`../../planning-conventions.md`](../../planning-conventions.md) §4): plans develop against mocked unit/component tests only (Vitest, Ktor testApplication, Wiremock, in-memory / mocked DB fakes, fixture-LLM); live smoke and in-cluster e2e are deferred to the separate **Stream T** integration-test suite.
>
> **Status (2026-06-24): P1–P4 CODE-COMPLETE (#49, reviewed).** All fourteen stages are merged with mocked specs green — sessions + SSE + `/v2` dispatch (P1), FE extraction + BFF cutover (P2), routing UX + typed actions + observability (P3), inbox + artifacts + discovery + feedback + audit (P4). **The remaining step is a Stream-T stage: Testing Stage 3.3** (Iris GitOps deploy + session-smoke on bp-dsk), which cuts `iris/v0.1.0…v0.3.0` (+ `iris-bff/v0.2.0…v0.4.0`) and **crosses master-plan M3**. Per-phase tags are deliberately deploy-gated, so they trail the code by that one stage.

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — envelope/v1 + iris-bff against new-golem** | `iris-bff` in local K3s: sessions in Postgres, SSE multiplex, transitional `/v2` dispatch. `envelope/v1` + `iris/v1` protos + `envelope-ts` generated, golden-sample gate green. | 1.1 / 1.2 / 1.3 / 1.4 | ~2–3 weeks |
| **Phase 2 — FE extraction + cutover to BFF** | `frontends/iris` extracted from agents-fe, built + deployed from kantheon, talking only to the BFF; generated envelope bindings adopted; daily-usable. | 2.1 / 2.2 / 2.3 / 2.4 | ~2 weeks |
| **Phase 3 — routing UX + typed actions + observability** | BFF calls `themis.understand()` per turn; RoutingPickChip round-trip live; typed actions (incl. `reask_agent`) + edit-and-resend + entity context + HandoffContext assembly + InvestigateChip on the BFF; Grafana dashboard. | 3.1 / 3.2 / 3.3 | ~2 weeks |
| **Phase 4 — inbox, artifacts, discovery, feedback** *(added 2026-06-12, cohesion review — the PD-2/PD-6/PD-7/PD-3 surfaces)* | Investigation inbox + NATS lifecycle stream + hypothesis-tree pane; pins & dashboards (`iris_artifacts`); `/v1/discover`; turn feedback + export; audit completion. | 4.1 / 4.2 / 4.3 | ~2–3 weeks |

Critical path: Phase 1 → Phase 2. Phase 3 hard-depends on Phase 2 *and* on Themis Phase 3 (`themis/v0.2.0`) — or co-development with it (Themis Stage 3.6's BFF stub IS Iris Phase 3 Stage 3.1's seed; one codebase, no throwaway stub). Phase 4 Stage 4.1 gates on Pythia exposing `ListInvestigations` + the lifecycle subject (Pythia Phase 1); **Stages 4.2/4.3 have no Pythia dependency and may run earlier if Pythia slips.** Midas Phase 3 Stage 3.5 consumes Stage 4.2's artifact system.

## 2. Pre-flight — before Phase 1 starts

| Pre-flight item | Status (2026-06-12) |
|---|---|
| `shared/proto` stable for import — **`AgentId` now lives in `common/v1`** (cohesion review D2; already checked in), so Stage 1.1 has no themis/v1 dependency at all | resolved 2026-06-12 |
| new-golem `/v2` dispatch target — **must be an in-cluster service on `bp-dsk`, not ai-platform-hosted** (no ai-platform, 2026-06-21). Not needed for the Phase-1 session-only smoke; required when dispatch is exercised | deferred to the Golem arc / a `/v2`-speaking backend on bp-dsk |
| Postgres available for iris-bff — **Kantheon PG, `iris` database** per kantheon-architecture §7.1; `deployment/local` infra stage provisions the single PG (+pgvector) + per-agent DBs + NATS + Keycloak dev realm | infra stage to schedule (owns the §7.1 operational consequences; needed no later than Phase 4 for NATS, Phase 1 needs only the `iris` DB) |
| TS proto codegen choice (ts-proto vs protobuf-es) confirmed by Bora | **open — decide in Stage 1.1 T1** |
| Recorded `/v2` SSE + envelope fixtures captured from a live new-golem session | small Bora/Claude task at Stage 1.1 start |

## 3. Phase 1 — envelope/v1 + iris-bff against new-golem

**Deployable at phase close.** `iris-bff` pod on the olymp `bp-dsk` cluster (kube-context `dsk`); a direct REST/SSE smoke runs session CRUD + a turn through it. *(Reworked 2026-06-21 — no ai-platform; the FE that drives full conversations lands in Phase 2.)*

### Stage 1.1 — envelope/v1 + iris/v1 protos + envelope-ts

**Goal.** Both protos compile to KT + TS; golden-sample gate proves v2 compatibility.

**Tasks (6).**
1. Pin TS codegen tool; wire `envelope-ts` packaging into `just proto` — **incl. common/v1 types** (AgentId, HandoffContext, BlockProvenance ride into the TS bundle; no themis/v1 in the codegen set).
2. Write `envelope/v1/envelope.proto` per contracts §1.1 (full file: Block incl. `provenance`, Chip oneof hosting RoutingPickChip + **InvestigateChip**; imports **common/v1 only**).
3. Write `iris/v1/iris.proto` per contracts §1.2 (incl. TurnPointer `current_view`/`applied_context` snapshot + `TurnOrigin`).
4. Capture golden samples: ≥10 recorded new-golem v2 envelopes (table/chart/markdown/plaintext, chips, clarification, drilldowns) into `shared/libs/ts/envelope-ts/test/fixtures/`.
5. Tests first: golden-sample round-trip specs (TS: parse via bindings; KT: JSON → proto → JSON) — then make them pass with the normalisation shim (flattened v2 format details → typed spec). `provenance`/`InvestigateChip` are additive — the gate must accept their absence in v2 samples.
6. Port `parseEnvelope` + format-directive interpreter into `envelope-ts`; unit tests.

**DONE.** `just proto` green; golden-sample CI job green on KT + TS.
**Dependencies.** None (common/v1 is already checked in — no themis/v1 dependency since the AgentId move).

### Stage 1.2 — iris-bff skeleton + session persistence

**Goal.** Module compiles, DB migrations run, session CRUD green.

**Tasks (6).**
1. Module skeleton: `agents/iris-bff` build.gradle.kts (ktor-configurator, otel-config, logging-config, jOOQ, Flyway), App.kt ≤45 lines per EXAMPLES.md §1b.
2. Flyway migrations per contracts §3 (`iris_sessions`, `iris_turns` incl. `origin`/`origin_ref`, `iris_snapshots`, `iris_v2_threads`, **`iris_audit` + `iris_feedback`** — tables from day one per cohesion review D6; write paths land in 1.3 / Phase 4); jOOQ codegen wired into the build.
3. Tests first: `SessionStoreSpec` against an in-memory / mocked DB fake — create/get/list, turn append with seq, snapshot/reset, discard semantics (real-PG fidelity deferred to the separate integration-test suite).
4. Implement `SessionStore` + `Snapshot` mechanics; make tests pass.
5. Session REST endpoints per contracts §2.1 + Keycloak bearer validation (validate-only); `HealthRoutes` with `/ready` gated on migrations.
6. K8s manifests `k8s/{base,overlays/local}`; lint clean.

**DONE.** `just test-kt iris-bff` green; `/v1/session` CRUD against local PG.
**Dependencies.** Stage 1.1 (proto bindings).

### Stage 1.3 — dispatch + SSE multiplex (transitional /v2)

**Goal.** Full turn flows through: POST `/v1/chat/stream` → new-golem → SSE out → TurnPointer persisted.

**Tasks (7).**
1. Tests first: `IrisStreamMuxSpec` — table-driven event mapping against recorded `/v2` SSE fixtures (ordering, completeness, heartbeat, error tail).
2. `GolemV2Client` (quarantined DTOs per contracts §5): session create, chat stream consumption (POST + streaming body parse), resume.
3. Envelope normalisation: v2 JSON → `envelope/v1` (reuse Stage 1.1 shim; shared module).
4. `IrisStreamMux` + `/v1/chat/stream` + `/v1/chat/turn`; sequence numbering; `done` synthesis on stream close.
5. `ConversationExcerpt` builder (last N turns from `iris_turns`) — transitionally informational (new-golem keeps its own thread state); unit tests.
6. Clarification resume: persist `pending_resume_token` + issuer on turn; `/v1/chat/resume` routes to issuer; component test for the full clarify→resume round-trip.
7. **Audit write at turn finalization** (cohesion review D6): hash-chained `iris_audit` insert (chain + Ed25519 sig; key from `iris.audit.signing-key-ref`); chain-verify unit test. Typed-action/export/escalation events follow with their features.
8. Component test: Ktor testApplication + Wiremock-golem + in-memory / mocked DB fake — full turn lifecycle incl. the audit row (real-PG fidelity deferred to the separate integration-test suite).

**DONE.** Component suite green; recorded-fixture parity on event streams.
**Dependencies.** Stage 1.2.

### Stage 1.4 — deploy + live smoke

> **Reworked 2026-06-21 — no ai-platform.** kantheon deploys to its own **olymp `bp-dsk`** cluster (kube-context `dsk`; GitOps via ArgoCD; chart in kantheon, app/values in olymp). The original agents-fe-driven smoke is void. Phase-1 smoke is a **direct REST/SSE smoke** (FE arrives in Phase 2). Group A (review-deferred hardening) + Group B (deploy/smoke) detailed in [`tasks-p1-s1.4-deploy-smoke.md`](./tasks-p1-s1.4-deploy-smoke.md).

**Goal.** BFF live on `bp-dsk`; a direct REST/SSE smoke completes session CRUD + a turn through it.

**Tasks.**
1. **Group A** — review-deferred hardening (Exposed audit writer, Secret-loaded signing key, JWKS verify). **Done 2026-06-21** (iris-bff 61 tests green).
2. Helm chart `agents/iris-bff/k8s` (env-agnostic, mirrors golem); build + push `ghcr.io/boraperusic/iris-bff`.
3. olymp app `clusters/bp-dsk/apps/iris-bff` (config.json + values; ghcr-pull ns selector); ArgoCD sync (or interim direct `helm install --context dsk`).
4. Live smoke: `/ready` → `POST/GET /v1/sessions` → turn append; then provision the `iris` DB (CNPG + `pg-iris`) and re-smoke against PG. OTel deferred to kantheon#27.
5. Fix-forward findings; tag.

**DONE.** Tag `iris-bff/v0.1.0`. **Phase 1 DONE.**
**Dependencies.** Stage 1.3; olymp `bp-dsk` cluster.

## 4. Phase 2 — FE extraction + cutover to BFF

**Deployable at phase close.** `frontends/iris` served from kantheon K3s (nginx), all traffic via BFF; agents-fe frozen.

### Stage 2.1 — filter-repo extraction + kantheon build

**Tasks (6).** 1. Dry-run `git filter-repo --path frontends/agents-fe` on scratch ai-platform clone. 2. Import into `kantheon/frontends/iris` (one-way; history preserved). 3. Kantheon build wiring: `just build-fe/test-fe/lint-fe iris`; CI job. 4. Vitest + oxlint/eslint green unmodified. 5. Rename pass (`agents-fe` → `iris` in package.json, titles, i18n keys; store names unchanged). 6. README for `frontends/iris`.

**DONE.** FE builds + tests green from kantheon; CI runs it.

### Stage 2.2 — envelope-ts adoption + service re-point

**Tasks (6).** 1. Replace `src/types/envelope.ts` with `envelope-ts` imports; keep local `DisplayState`. 2. Golden-sample rendering specs (fixtures from Stage 1.1 render through formatCatalog). 3. Split `agentService.ts` → `services/irisStream.ts` + `services/typedAction.ts` targeting BFF endpoints (contracts §2). 4. Strip direct platform services (llmGateway/metadata/mcpClient) or stub behind BFF flags. 5. Single `VITE_BFF_BASE_URL` config; delete `/golem` etc. proxies. 6. SSE event-name switch (`step/envelope/error/done`) in chatStore consumption; lifecycle specs updated.

**DONE.** FE talks only to BFF in dev; vitest green.

### Stage 2.3 — session UX on the BFF

**Tasks (6).** 1. Session list/create/switch UI against `/v1/sessions`. 2. History hydration from `iris_turns` (reload restores conversation). 3. Edit-and-resend wired to `edit_resend` typed action (BFF snapshot + discard; FE optimistic). 4. `/reset` + snapshot-undo flow. 5. Slash-command audit: client-side ones unchanged; `/sql`, `/format` arm `ChatTurnRequest` flags; `/export` reads stored envelopes. 6. e2e component pass over the above in vitest + BFF component tests.

**DONE.** Multi-session daily-driver UX complete.

### Stage 2.4 — deploy + cutover

**Tasks (5).** 1. nginx image + k8s manifests; `just deploy-fe iris`. 2. Keycloak redirect URIs + CORS for the new origin. 3. Side-by-side week: iris-fe (via BFF) vs agents-fe (direct) — divergence log. 4. Flip default; mark agents-fe transitional-frozen in ai-platform README. 5. Tags.

**DONE.** Tags `iris/v0.1.0`, `iris-bff/v0.2.0`. **Phase 2 DONE.**

## 5. Phase 3 — routing UX + typed actions + observability

**Pre-flight.** `themis/v0.2.0` (Phase 3) closed **or** co-developed: Themis Stage 3.6's "Iris BFF stub" tasks are executed *here*, in `agents/iris-bff`, against Themis's fixture LLM. capabilities-mcp live with pythia + golem-erp fixtures.

### Stage 3.1 — Themis integration + routing dispatch

**Tasks (7).** 1. Tests first: `ThemisDispatchSpec` — resolution→dispatch, needs_user_pick→chips, refusal→error envelope (incl. `NO_ENTITLED_AGENT`), multi-question→decomposition handling. 2. `ThemisClient` (`understand()` per themis/v1; profile CHAT_QUICK; excerpt + entity context in; **user bearer forwarded** — roles transport D3). 3. **`HandoffContext` assembly on every dispatch** (PD-1 rule per iris contracts §1.2: previous TurnPointer + EntityContext → `prior_context` to Themis + handoff to the routed agent); unit tests on the assembly. 4. `AgentDispatcher` keyed by `chosen_agent_id` (registry: golem-v2 transitional + future native). 5. RoutingPickChip emission path (chips envelope, `alternates_offered` tracked on turn). 6. Reissue with `routing_hint` → Layer 0 verified against Themis fixture. 7. `RefusalWithGaps` rendering + **decomposition rule (PD-13)**: decompose into sub-question chips ONLY on `SPLIT`; `KEEP_TOGETHER` proceeds whole (rationale as hint bubble).

**DONE.** Chip round-trip green end-to-end against fixture LLM — this satisfies Themis Stage 3.6 tasks 2–3 simultaneously.

### Stage 3.2 — typed actions + chips + entity context

**Tasks (8).** 1. `/v1/action` per contracts §2.4: sort/filter/paginate re-issue against producing agent (transitional: new-golem path). 2. `select_row` + drilldown invocation flow. 3. Static chips from capabilities-mcp + metadata queries; dynamic chips forwarded from agent envelopes. 4. EntityContext updates from envelope `entity_context` → session; excerpt includes bindings; **PD-4 `applied_context` comparison: scope indicator + mismatch warning bubble**. 5. RoutingPickChip SFC + chip-kind discriminated rendering in FE. 6. **`reask_agent` typed action (PD-14)**: agent badge on every bubble (capabilities cache), picker pre-sorted by `RoutingDecision.alternates` with `why` strings, re-issue with `routing_hint` + `iris_feedback.corrected_agent_id` upsert. 7. **InvestigateChip (PD-1)**: FE rendering + chip-click re-issue (`routing_hint=pythia` + handoff) + the BFF-side always-on "Investigate this" drilldown action on table/chart blocks; audit `escalation` event. 8. Component tests across the action surface.

**DONE.** Action channel + chips + context + escalation live via BFF.

### Stage 3.3 — observability + hardening + docs

**Tasks (6).** 1. Metrics per architecture §10.1. 2. Grafana dashboard `iris-bff` (turn latency, dispatch breakdown, pick-rate, stream gauge). 3. Trace audit: SPA→BFF→Themis→agent single trace. 4. Load sanity: 20 concurrent streams; bounded buffers verified. 5. Update `iris-design.md` (fold-in: agents-fe heritage, transitional adapter, current_display/current_view pin); READMEs. 6. Tags.

**DONE.** Tags `iris-bff/v0.3.0`, `iris/v0.2.0`. **Phase 3 DONE — routing UX shipped.**

## 5a. Phase 4 — inbox, artifacts, discovery, feedback (added 2026-06-12, cohesion review)

**Deployable at phase close.** The PD-2/PD-6/PD-7/PD-3 product surfaces live; audit complete (verify endpoint + retention). Midas Stage 3.5 unblocked.

**Pre-flight.** Stage 4.1 only: Pythia `GET /v1/investigations` + `pythia.lifecycle.{user_id}` live (Pythia Phase 1); NATS reachable from the BFF (`deployment/local` infra stage). Stages 4.2/4.3: none beyond Phase 3.

### Stage 4.1 — investigation inbox + lifecycle stream

**Tasks (6).**
1. Tests first: inbox aggregation spec — Pythia list ⋈ `iris_turns` (session names, turn refs, `origin` badges), 12→5 status mapping per contracts §2.7.
2. NATS lifecycle subscriber (`pythia.lifecycle.{user_id}`) + degrade-to-polling fallback (`iris.inbox.poll-fallback-s`).
3. `GET /v1/inbox` + `GET /v1/inbox/stream` SSE fan-out per contracts §2.7.
4. FE: header badge (Running + Needs-input counts) + dockview inbox panel (question, status, elapsed, cost-so-far, session link); reattach via Pythia `/events?from_seq=N`.
5. Needs-input rows → existing chat clarification UI; control calls proxied to Pythia's per-state endpoints.
6. Hypothesis-tree pane (debug-grade per PD-2): collapsible tree from `InvestigationArtifact.hypotheses` + live updates from stream events; flat step list per hypothesis (no DAG pane).

**DONE.** Scheduled + interactive investigations visible, reattachable, resumable from the inbox; tree renders live.
**Dependencies.** Phase 3; Pythia Phase 1 (`pythia/v0.1.0`).

### Stage 4.2 — artifacts: pins & dashboards

**Tasks (6).**
1. Tests first: pin capture + refresh specs — Golem-kind via typed-action re-issue + display-state re-apply; Pythia-kind via `replay`/`reproduce`; failure → explicit stale/error state, never silently wrong.
2. `iris_artifacts` migration + repository (contracts §3.3).
3. `POST/GET/PATCH/DELETE /v1/artifacts` + pin-capture assembly (envelope snapshot + ViewProvenance + applied_context + display state) per contracts §2.8.
4. `POST /v1/artifacts/{id}/refresh` + `GET /v1/dashboards/{id}/open` (parallel per-pin refresh SSE, `refresh_mode` honored; owner-OBO).
5. FE: pin action on table/chart bubbles; dashboard view (layout, template support incl. `template_id`/`params_json`); tiles render refreshed-at + provenance ⓘ + scope indicator.
6. Audit `artifact_refresh` events; metrics (`iris_artifact_refresh_total{result}` etc.); component tests.

**DONE.** Pin → dashboard → reopen → refresh round-trip green; Midas Stage 3.5 consumable.
**Dependencies.** Phase 3 (typed-action surface). No Pythia dependency for Golem-kind pins.

### Stage 4.3 — discovery, feedback, audit completion

**Tasks (6).**
1. `GET /v1/discover` per contracts §2.6 (role-filtered DomainCards from the capabilities cache; `non_routable` excluded) + FE first-run/empty-session panel + suggested-question chips.
2. Tests first + `POST /v1/turns/{id}/feedback` (upsert per contracts §2.9); FE 👍/👎 + one-tap reason picker.
3. `just feedback-export` per-agent schema adapters → `eval/candidates/` (Themis labeled routing examples from wrong-agent + correction pairs).
4. Audit completion: `GET /v1/audit/verify?segment=` (admin-gated) + whole-segment retention/archive job + signing-key Secret runbook.
5. Metrics: `feedback_total{agent_id, verdict, reason}`, inbox/discover counters; Grafana dashboard extension.
6. Docs (architecture/contracts fold-ins, READMEs) + tags.

**DONE.** Tags `iris-bff/v0.4.0`, `iris/v0.3.0`. **Phase 4 DONE — Iris arc shipped.**
**Dependencies.** Stage 4.2 (audit sees artifact refreshes); Stage 3.2 (`corrected_agent_id` flows from `reask_agent`).

## 6. Cross-cutting

| Item | Where |
|---|---|
| Themis Stage 3.6 stub == Iris Stage 3.1 (single implementation; Themis plan updated to point here) | both plans cross-reference |
| `bff-base` extraction (Sysifos sibling) | NOT this arc — extract when sysifos-bff starts (Midas plan) |
| `iris-design.md` reality note (agents-fe source) | added 2026-06-12; full revision in Stage 3.3 |
| Golden-sample fixture refresh discipline (re-record on new-golem envelope changes) | CI job owns; noted in envelope-ts README |

## 7. Out of scope

- Kotlin Golem / Pythia native clients (their arcs add them to `AgentDispatcher`).
- The **plan-DAG pane** (steps render as a flat list per hypothesis; `kantheon-v1.1.md` §2 has the pickup trigger). *(The PD-2 inbox + hypothesis tree and the PD-6 artifact system — formerly amendment notes here — are now Phase 4, Stages 4.1/4.2.)*
- Sysifos. Hebe (its arc consumes the BFF as a headless client).
- Multi-tab `update_tab_id` orchestration beyond what agents-fe already does.
- Mobile layout, offline, i18n beyond cs/en.

## 8. Open questions / Bora-owned content

| Item | Blocking | Note |
|---|---|---|
| TS proto codegen tool (ts-proto vs protobuf-es) | Stage 1.1 T1 | Claude proposes ts-proto (mature, idiomatic interfaces); confirm |
| Static-chip curation (which suggested topics pre-turn) | Stage 3.2 | structural source = capabilities-mcp + metadata; content = Bora |
| Keycloak realm/client names for the new origin | Stage 2.4 | config-only |
| Side-by-side acceptance bar for Phase 2 cutover | Stage 2.4 | propose: zero envelope-rendering divergences over one week of daily use |

## 9. Phase progression checklist

- [x] **Stage 1.1** — envelope/v1 + iris/v1 + envelope-ts + golden samples. *(Done 2026-06-17 — [`tasks-p1-s1.1-envelope-proto.md`](./tasks-p1-s1.1-envelope-proto.md); ts-proto via buf, snake-tolerant proto-canonical JSON, KT+TS golden gates green.)*
- [x] **Stage 1.2** — BFF skeleton + sessions. *(Done 2026-06-17 — [`tasks-p1-s1.2-bff-sessions.md`](./tasks-p1-s1.2-bff-sessions.md); Ktor + Exposed/Flyway, SessionStore (in-memory tested + Exposed binding), session REST + bearer, 15 tests green. ExposedSessionStore live-PG validation deferred to integration.)*
- [x] **Stage 1.3** — dispatch + SSE mux. *(Done 2026-06-17 — [`tasks-p1-s1.3-dispatch-sse.md`](./tasks-p1-s1.3-dispatch-sse.md); GolemV2Client + V2 SSE parser, IrisStreamMux (v2→envelope/v1, done synthesis), chat routes, ConversationExcerpt, hash-chained Ed25519 audit, clarify→resume. 33 tests green. Live /v2 HTTP + Exposed audit writer deferred to 1.4/integration.)*
- [~] **Stage 1.4** — Group A hardening + Helm chart + **deploy to olymp bp-dsk (GitOps, PG-backed, smoke green) DONE 2026-06-21** ([`tasks-p1-s1.4-deploy-smoke.md`](./tasks-p1-s1.4-deploy-smoke.md)). Remaining: **T-B5** OTel (kantheon#27) + tag **`iris-bff/v0.1.0`** (on Iris PR merge) → **Phase 1 DONE**.
- [x] **Stage 2.1** — FE into kantheon + build wiring. *(Done 2026-06-21 — [`tasks-p2-s2.1-fe-extraction.md`](./tasks-p2-s2.1-fe-extraction.md); one-time `git subtree` extraction from ai-platform agents-fe @54ba4d73, history preserved; renamed golem-frontend→iris; just build-fe/test-fe/lint-fe + frontend-iris CI job; green baseline 132 vitest + tsc + build + lint.)*
- [x] **Stage 2.2** — envelope-ts adoption + re-point. *(Done 2026-06-21 — [`tasks-p2-s2.2-envelope-ts-bff.md`](./tasks-p2-s2.2-envelope-ts-bff.md); FE fully on `@kantheon/envelope-ts` (v1), BFF-only client (irisStream + Phase-3 typedAction stub), single `VITE_BFF_BASE_URL`, SSE step/envelope/error/done. Merged in PR #44.)*
- [x] **Stage 2.3** — session UX. *(Done 2026-06-23 — [`tasks-p2-s2.3-session-ux.md`](./tasks-p2-s2.3-session-ux.md); left `SessionRail` (list/switch/new/undo), history hydration (turn pointers + per-turn envelopes → bubbles tagged `turnId`), `edit_resend` via `POST /v1/action` (BFF `restoreLatestSnapshot` + `/undo`; FE `editAndResend`), `/reset` + snapshot-undo, slash audit (`/reset` added, `/sql` warns). iris-bff 76 tests; FE 183 vitest + build/lint/tsc green.)*
- [~] **Stage 2.4** — deploy ASSETS. *(DONE 2026-06-23 — [`tasks-p2-s2.4-deploy-cutover.md`](./tasks-p2-s2.4-deploy-cutover.md): FE Helm chart `frontends/iris/k8s` (same-origin `/bff` nginx, Gateway HTTPRoute, ConfigMap runtime-env), `publish-fe-image` recipe, `helm lint`/`template` green; olymp `bp-dsk` app manifests prepared.)* The **live deploy + smoke + Phase-2 tags moved to the Testing arc — Stage 3.3** ([`../testing/tasks-p3-s3.3-iris-deploy-smoke.md`](../testing/tasks-p3-s3.3-iris-deploy-smoke.md)). **Phase 2 DONE — `iris/v0.1.0`** when Testing Stage 3.3 lands (crosses M3).
- [x] **Stage 3.1** — Themis integration (== Themis Stage 3.6 stub) + HandoffContext assembly. *(Done 2026-06-23 — [`tasks-p3-s3.1-themis-routing.md`](./tasks-p3-s3.1-themis-routing.md); resolve-then-dispatch: HttpThemisClient (proto-JSON `/v1/resolve`, OBO bearer), HandoffAssembler (PD-1, 50-cap/1KiB), AgentDispatcher + GolemV2AgentClient, RoutingPickChips + `alternates_offered` (V2 migration) + routing_hint Layer-0 reissue, RefusalWithGaps + PD-13 SPLIT/KEEP_TOGETHER, audit carries RoutingDecision. iris-bff 108 tests.)*
- [~] **Stage 3.2** — typed actions (incl. reask_agent) + chips + context + InvestigateChip. *(**BFF COMPLETE 2026-06-23** (T1/T2/T3/T4/T6/T7): sort/filter/paginate BFF-side shaping (`TypedActionDispatcher`+`TableShaping`, `current_display`, refetch via `GolemV2Client.reissueAction`); `select_row` drilldown→new bubble; static chips (`StaticChipSource`+`static-chips.yaml`) + `chip_invocation`→normal turn; EntityContext read-back + PD-4 scope-mismatch WARNING; `reask_agent` (PD-14)→`iris_feedback.corrected_agent_id` (new `FeedbackStore`); `InvestigateChip` (PD-1) escalation→routing_hint=pythia→NO_AGENT_CLIENT. **FE T5 + component-pass T8 deferred (separate FE workstream).** [`tasks-p3-s3.2-typed-actions.md`](./tasks-p3-s3.2-typed-actions.md).)*
- [~] **Stage 3.3** — observability + docs. *(**BFF DONE 2026-06-23**: `RoutingMetrics` (Micrometer/Prometheus) on the dispatch path + `GET /metrics`; Grafana dashboard `agents/iris-bff/k8s/grafana-dashboard.json`; 20-concurrent-turn load sanity. Single-trace OTel → kantheon#27. **Tags `iris-bff/v0.3.0`/`iris/v0.2.0` pending FE + PR merge.** [`tasks-p3-s3.3-observability.md`](./tasks-p3-s3.3-observability.md).)*
- [x] **Stage 4.1** — inbox + lifecycle stream + hypothesis tree. **Code-complete 2026-06-24, fake-backed** (live Pythia/NATS + reattach + control proxy → Pythia arc). *([`tasks-p4-s4.1-inbox.md`](./tasks-p4-s4.1-inbox.md); phase entry point [`tasks-p4-overview.md`](./tasks-p4-overview.md).)*
- [x] **Stage 4.2** — artifacts: pins & dashboards. **DONE 2026-06-24** (Pythia-kind replay → Pythia arc). *([`tasks-p4-s4.2-artifacts.md`](./tasks-p4-s4.2-artifacts.md).)*
- [x] **Stage 4.3** — discovery + feedback + audit completion. **DONE 2026-06-24 — tags `iris-bff/v0.4.0` + `iris/v0.3.0` pending PR merge.** *([`tasks-p4-s4.3-discovery-feedback.md`](./tasks-p4-s4.3-discovery-feedback.md).)*

---

*Plan owner: Bora. Iris arc planned 2026-06-12. Per-stage task lists land at `docs/implementation/v1/iris/tasks-p<n>-s<n.m>-*.md` after this plan is reviewed.*
