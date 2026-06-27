# Kantheon v1.1 — deferred items

> **Status:** living document, opened 2026-06-12 during the product-design convergence sessions. Cross-cutting; lives next to `planning-conventions.md`.
>
> **Purpose.** Everything explicitly mentioned and *deliberately deferred* during v1 design lands here, so nothing relies on memory. Each item records where it was deferred, why, and what triggers it. This is **not** the open-issues list — unresolved product questions stay in [`../design/product-design-issues.md`](../design/product-design-issues.md); an item enters this doc only once a v1 decision consciously pushed it out.
>
> **Graduation rule.** When an item is picked up, it gets a normal arc/stage home (architecture/contracts/plan or a task list) and is struck through here with a pointer.

---

## 1. Cross-agent contracts

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Peer artifact-read API** (`GET /v1/artifact/{ref}` on every agent) | PD-1 resolution | v1 uses inline `HandoffContext` payload — bounded, no new cross-agent surface or authz story. `source_turn_ref`/`envelope_ref` already carry the refs. | First case where inline payload is too small — e.g. Pythia wanting the full step record of a Golem turn, or conclusion-level drilldown into source artifacts |
| **Shared chained-log library** (extract Hebe `receipts` / `iris_audit` hash-chain into `shared/libs/kotlin`) | PD-8 resolution (implicit) | Two implementations of the same shape (Hebe PG receipts port, `iris_audit`) is acceptable duplication at v1 scale; premature extraction calcifies. | The second implementation lands and diverges, or a third consumer appears (Golem/Pythia audit projections) |
| **Zero-trust hop re-validation** (re-validate JWT at every gRPC hop, not just the MCP edge) | PD-8 / inherited from ai-platform `PipelineContext` v1 trust model | v1 trusts upstream-populated context inside the cluster; mTLS/network isolation is the boundary. | Multi-tenant deployment, or any compliance requirement naming zero-trust |
| ~~**Domain-free `cz.dfpartner.common.v1.ResponseMessage` extraction (ai-platform side)**~~ | Cohesion review 2026-06-12 (finding 2.1 / D1) | **Closed 2026-06-12 by the platform fork:** the kantheon stand-in is promoted to canon; forked services retarget to it on arrival (fork plan Stage 1.2). No ai-platform extraction will happen — ai-platform is maintenance-only. | — graduated: [`v1/fork/plan.md`](./v1/fork/plan.md) Stage 1.2 |
| **Agent-held OBO token exchange/refresh for long runs** | Cohesion review 2026-06-12 (finding 2.6 / D7); `kantheon-security.md` §2.1 | v1 fails closed: mid-run token expiry parks the investigation `AWAITING_USER_INPUT` with a Rule-6 message; resume runs under the fresh bearer. | First real investigation failing on token expiry at a customer |
| **`NumberFormatSpec` on `envelope/v1` `TableColumnSpec`** (locale-aware number-format intent: style/currency/min-max fraction digits/grouping) | Iris Phase 2 Stage 2.2 T1 (2026-06-21) | The v2 FE wire carried a `number` formatting intent the renderer fed to `Intl.NumberFormat`; envelope/v1 `TableColumnSpec` dropped it. The FE keeps the capability as a cast-only FE-local (`FeNumberFormatSpec`) the wire never populates — so **backend-driven locale number formatting is inert over v1** until the proto regains the field; only the `@deprecated` printf `format` fallback works. | Add `NumberFormatSpec` to `envelope/v1` `TableColumnSpec` (proto + envelope-ts regen + drop the FE-local cast) when a backend needs to drive table number formatting over the v1 wire |

## 2. Inbox & investigation UX

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Plan-DAG pane** | PD-2 resolution (hypothesis tree IS in v1, debug-grade) | Steps render as flat lists per hypothesis; tree covers the debugging need. | Hypothesis tree proves insufficient for debugging parallel branches / step dependencies |
| **Generic "all runs" inbox** (beyond `agent_kind = INVESTIGATOR`) | PD-2 resolution | Golem turns finish in seconds; nothing else is long-running yet. API shape doesn't preclude widening. | Any second long-running agent kind (e.g. heavy Midas report renders surfacing as runs) |
| **Hebe pushes for *interactive* investigations** (Telegram "your investigation finished" for runs the user started in Iris) | PD-2 resolution; Hebe arc PD-2 note | `pythia.lifecycle.{user_id}` NATS subject is designed for exactly this — zero Pythia change needed. Hebe v1 only delivers its own scheduled runs. | Hebe arc P4 shipped + first user request; small Hebe routine subscribing to the lifecycle subject |

## 3. Feedback loop

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Implicit feedback signals** (edit-resend shortly after answer, abandoned clarifications, chip-ignored-then-rephrased) | PD-3 resolution | Real but noisy; explicit loop must prove the export pipeline first. | Explicit volume too low to be useful, or curation workflow established and hungry |
| **Scheduled feedback exporter** (cron job replacing `just feedback-export`) | PD-3 resolution | Manual recipe is fine while curation is manual anyway. | Curation cadence becomes regular (e.g. weekly); natural Hebe routine candidate |
| **Runtime routing re-weighting from feedback** | PD-3 resolution | Needs volume we won't have; feedback-driven runtime behavior is undebuggable at v1 scale. | Corpus shows persistent, statistically meaningful misroute patterns that rules/prompt fixes don't close |

## 3a. Artifacts & dashboards (PD-6)

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Scheduled artifact refresh** (Hebe routine kind `artifact_refresh`) | PD-6 resolution | v1 refresh modes are `manual`/`on_open` — the user is present, OBO is natural. A scheduler inside iris-bff would duplicate Hebe. | Hebe arc P4 shipped + first "refresh my dashboard nightly" request |
| **Dashboard-shared parameters** (one date picker driving all pins) | PD-6 resolution | Per-pin params cover v1; shared binding needs param unification across heterogeneous pins. | First multi-pin dashboard where editing each pin's period separately demonstrably annoys |
| **Dashboard sharing across users** | Midas arc §11.4 (pre-existing) + PD-15 | Per-user in v1; artifact refs already stable/unguessable so sharing won't remodel. | PD-15 resolution |

## 4. Hebe

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Shared Telegram bot + chat-id→instance router** (one bot for all instances) | Hebe `architecture.md` O-1 | Per-instance bot token in v1 — N BotFather registrations is fine for few instances. | Instance count makes per-bot management annoying (~10+) |
| **Instance provisioning automation** (`hebe-operator` / provisioner job) | Hebe `architecture.md` O-2 | Manual runbook (`contracts.md` §4.4) while instances are few. | Instance count grows, or provisioning errors occur twice |
| **Hebe mcp-server exposing constellation data** (IDE asks Hebe about investigation results) | Hebe `architecture.md` O-4 | Out of v1 scope; touches sharing semantics. | Revisit together with PD-15 (sharing) |
| **Console API proto alignment** (full `hebe/v1` surface for the web console) | Hebe `contracts.md` §1.2 | v1 protos cover only boundary-crossing types (Routine/RoutineRun); console REST stays as-is. | Console rework, or a second console consumer |
| **Workspace file-watching / manual editing on k8s** | Hebe `architecture.md` §5.3 | `workspace_files` in PG; the web console is the k8s editing surface. Files remain a local-profile affordance. | Concrete need to bulk-edit workspace outside the console |

## 5. Platform / cost

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Prometheus-native cost-attribution** (first-class tags instead of best-effort headers) | Hebe `contracts.md` §5 (`[llm]`); relates to open PD-11; **re-homed 2026-06-12** — the LLM gateway forks in as `services/prometheus`, so this is a kantheon backlog item, not a cross-repo ask | Hebe sends `X-Cost-Center`/`X-Turn-Ref` headers; gateway may ignore (graceful degrade). | PD-11 resolution (budget enforcement needs reliable attribution) |
| **Per-user/org budget ceilings** (enforcement, not just visibility) | PD-11 resolution (2026-06-12) | Visibility (PD-2 cost-so-far) + per-investigation `Constraints` + the `AWAITING_BUDGET_DECISION` gate cover v1; org-level enforcement needs Prometheus-native attribution (above). | Prometheus-native attribution lands + first real cost-overrun incident or customer ask |
| **Prometheus tier routing** (model-tier selection per caller/turn) | fork decision 2026-06-12 — absorbed from the never-written `aip-v1-gateway-worker-plan.md` | Forked as-is; tier routing was a pending ask against ai-platform's llm-gateway and travels with the fork. | Pythia Phase 3 (the original trigger) or first multi-tier cost pressure |

## 6. Shem authoring toolchain (PD-12 — direction locked, build deferred)

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Manifest schema validation in CI** | PD-12 resolution | Trivial but pointless before a second Shem exists. | Golem-ERP shipped (the trigger for the whole section) |
| **Bootstrap-from-metadata-mcp generator** (ShemManifest skeleton: tables, columns, terminology candidates) | PD-12 resolution | Requirements come from hand-authoring Golem-ERP first. | Golem-ERP shipped; second Shem (Golem-Investment) being onboarded |
| **Per-Shem eval corpus + CI gate** (template runs corpus against candidate manifest pre-deploy) | PD-12 resolution | Consumes PD-3's `eval/candidates/` over time; needs a proven template to gate against. | Golem-ERP shipped + first manifest-drift incident or PD-3 corpus reaching useful size |
| **Role-creation onboarding step** (`kantheon-domain-<shem>` realm role per new Shem) | PD-12 resolution (from PD-8) | Manual Keycloak step in v1 — fine at low Shem count. | Folded into the bootstrap generator when it lands |

## 7. Sharing & collaboration (PD-15 — explicit v2 deferral)

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Read-only sharing of sessions / artifacts** | PD-15 resolution | v1 constraint honored: stable unguessable refs everywhere (`iris_artifacts`, sessions) — sharing adds without remodeling. | v2 scope; first customer org with multiple analysts asking |
| **Comments / multi-user collaboration / session search** | PD-15 resolution | Org-scale workflows; not v1's single-analyst thesis. | v2 scope |
| *(related: dashboard sharing §3a, Hebe MCP exposure §4 — both keyed to this section)* | | | |

## 8. Ariadne / model graph

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Drill maps — definition & usage redesign** | Fork Stage 2.1 (Bora, 2026-06-13) | The forked `drill_map.ttr` shape (`from_pattern`/`to_pattern`/`arg_mapping`/`explicit`/`display`) and how it surfaces in `ModelBundle.drillMaps` is not product-satisfactory; the concept needs a fresh definition before it's worth wiring or testing. The forked `DrillMapDetail` proto + loader stay in tree (no deletion), but the loading is **not exercised** at v1 — the `GetModelSpec` "drill maps … land in ModelBundle.drillMaps" test was removed at 2.1 to unblock the suite (the failing modeler-0.4.0 fixture-shape case). | A product decision on what drill maps mean and how a turn consumes them (Golem/Iris drill-chip flow); re-add the loader test against the redesigned shape then |
| **`model-ttr` seed completeness** | Fork Stage 2.1 (review-003, 2026-06-13) | The committed `model-ttr/` seed is internally inconsistent: several packages (`artikl`, and the packages whose relations reference `produkt`, `podprodukt`, `subjekt`, `dodací_místo`, `obchodní_kanál`, … — 15 distinct entities) ship `db.ttr` + `er.ttr` *relations* but no `def entity` definitions, so the resolver reports them as dangling refs. `ModelTtrLoadSpec` was scoped to the self-contained `ucetnictvi` package (with a `ScopedStorage`/`SEED_PACKAGES` allowlist to widen later) so the loader/reconciler path stays guarded; the *full* tree is not asserted error-free. | Re-convert / restore the missing `def entity` files from the `ai-models` source so every referenced `er.entity.X` resolves, then widen `ModelTtrLoadSpec.SEED_PACKAGES` (ideally to the whole tree) |

## 9. Charon / cross-engine schema fingerprint

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Charon `regenerate.py` map encoding → entries-wrapped** | Fork Stage 3.4 (Bora "full unify", 2026-06-15) | The canonical fingerprint was unified across Charon (`Integrity.kt`), Brontes (`ArrowIpcSerializer`), and Steropes (`fingerprint.py`) on the **entries-wrapped** map form `{key,value}` (what Arrow Java exposes), pinned by `shared/testdata/fingerprints/`. Charon's Kotlin runtime already produces this form; but Charon's *Python test-reference helper* `services/charon/src/test/resources/fixtures/integrity/regenerate.py` `children(map)` still returns the **flat** `[key_field, item_field]` form — inconsistent with its own Kotlin. Latent only: Charon's `IntegritySpec` reference schema has no map, so nothing fails today. | Stream B updates `regenerate.py` `children(map)` to the entries-wrapped form (`[pa.field("entries", pa.struct([key_field, item_field]), nullable=False)]`) and optionally adds a map row to `IntegritySpec` so Charon also pins it — at which point all four impls agree on maps in CI |
| **Worker batch `schema_fingerprint` as a cross-engine cache key** | Fork Stage 3.4 | Now that Brontes + Steropes both stamp the canonical fingerprint, `ResultBatch.schema_fingerprint` IS cross-engine-stable and could key a shared result cache (worker.proto names this use). No consumer uses it that way at v1. | Theseus / query-runner result caching arc |

## 10. Metis / model estimation

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Process-pool fit isolation** (true per-fit process isolation + cancellation) | Metis P2 (`fit_runner.py`); review (Metis merge, 2026-06-15) | Architecture §4/§5 calls for per-RPC process-pool isolation so a hung optimizer cannot block the server. v1 uses a shared `ThreadPoolExecutor(max_workers=4)` with a deadline — statsmodels/Prophet release the GIL, so threads give real concurrency, but `future.result(timeout=…)` does **not** cancel the underlying thread: a wedged fit keeps a pool slot, so ≥4 wedged fits would stall further fits (single-replica, session-pod-local — same blast radius as a pod restart). | First wedged-fit incident, or Metis moved off single-replica; swap to a `ProcessPoolExecutor` for fit *invocation* (keep model objects in the main process to avoid pickling statsmodels/Prophet results) |
| **§7 observability completeness** (workspace gauges, eviction + auto-order metrics) | Metis P3; review (2026-06-15) | The fit/project/simulate/diagnose counters + `metis_fit_duration_ms` summary are emitted (names aligned to architecture §7 at review). Still missing: `metis_workspace_{dfs,models,bytes}` gauges, `metis_workspace_evictions_total{reason}`, and the `metis_auto_order_search_ms` histogram. The data exists (`Workspace.status()`, the sweeper, ARIMA auto-order) but isn't wired to metrics yet; the OTLP export path (architecture §7) also still layers on later. | Metis dashboards being built, or the first capacity/eviction question that needs the gauges |
| **Pre-parse input caps** (`metis.inline-max-bytes` guard; `metis.fit.arima.{max-order,seasonality-candidates}` as server config) | Metis P2/P3; review (2026-06-15) | Contract §5 lists an inline-payload byte cap and server-side ARIMA auto-order knobs. v1 bounds inline payloads only post-parse (`max_fit_rows` → `RESOURCE_EXHAUSTED`, plus the gRPC max-message limit) and takes ARIMA bounds per-request (`ArimaParams.max_order`) with model-internal defaults. | A large-inline-payload OOM before the row cap bites, or a deployment needing to retune auto-order bounds without a client change |

## 11. theseus-mcp / audit provenance

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **`query` echoes executed SQL + exact total rows** (for ViewProvenance) | Fork Stage 3.6 T5 (audit-derivability paper-check, 2026-06-15) | `iris_audit`'s `ViewProvenance.sql` / `total_rows` are derivable today only with caveats: the **`compile`** tool returns `compiledSql`, but the **`query`** tool echoes neither the executed/translated DB SQL (only the caller's `source`) nor the exact row total when the result is row-limited (`rowCount` is post-truncation + a `truncated` flag). v1 is fine: agents on the plan-cache path compile-then-run (so they hold `compiledSql`), and provenance tolerates a truncation-bounded total. Not blocking — Iris isn't built yet. | Iris audit needs exact provenance SQL on the *direct* `query` path → add additive `executedSql` + `totalRows` fields to the `query` response (backward-compatible). See [`../architecture/iris/contracts.md`](../architecture/iris/contracts.md) §3.1. |

## 12. Fork observability / fabric-infra (fork Stage 4.1)

| Item | Deferred in | Why deferred / v1 stance | Trigger to pick up |
|---|---|---|---|
| **Fabric-infra dashboards + alerts for the forked stack** (per-service RED panels + `run_query` latency) | Fork Stage 4.1 T3 (2026-06-17) | Dashboard/alert *definitions* are fabric-infra-owned (inherited decision #8) — no server-side Grafana/alert manifests live in this repo. The wanted panels/alerts are filed as a **wishlist** in [`../architecture/fork/observability.md`](../architecture/fork/observability.md), not built. | fabric-infra picks up the wishlist; or first production incident needing the `run_query` latency panel |
| **Cross-pod trace-nesting verification** (theseus-mcp → Theseus → Proteus/Argos/Kyklop → Brontes/Steropes as one Tempo trace) | Fork Stage 4.1 T3 | Manual orchestration spans cover the in-process seam (theseus-mcp tool boundary + Theseus); leaf services rely on gRPC auto-instrumentation. The component test asserts in-process nesting only; the live all-hops trace in Tempo is integration-suite territory (planning-conventions §4). | The separate integration-test suite runs; or a real cross-pod latency question |
| **Worker `cost_hints` in a capability manifest** | Fork Stage 4.1 T4 | Workers (Brontes, Steropes) are not MCP tools and carry no capability manifest, so the manifest-`cost_hints` half of the Charon/Metis idiom has no home at the worker level; read-out baselines live in the worker READMEs instead. End-to-end `run_query` cost stays on `theseus.query:v1` (DB-fetch-dominated). | A worker becomes a first-class capability, or read-out (not DB fetch) becomes the measured bottleneck |

---

*Doc owner: Bora. Append on every "deferred to v1.1 / v1.x / v2" decision; strike through with a pointer when an item graduates into an arc.*
