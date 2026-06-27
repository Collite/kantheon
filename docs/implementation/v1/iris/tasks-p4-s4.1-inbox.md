# Iris Phase 4 Stage 4.1 — investigation inbox + lifecycle stream + hypothesis tree

> **Goal (plan §5a Stage 4.1).** The inbox is a **view over Pythia's persisted state** — no Iris-side investigation store. The BFF aggregates `pythia GET /v1/investigations?user_id=…`, joins session names / turn refs / `TurnOrigin` badges from its own `iris_turns`, exposes `/v1/inbox` + `/v1/inbox/stream` (SSE, fed by a NATS `pythia.lifecycle.{user_id}` subscriber with polling fallback), and the FE renders a header badge + dockview inbox panel + a debug-grade hypothesis-tree pane. **Scheduled + interactive investigations visible, reattachable, resumable from the inbox; tree renders live.**
>
> **Companions.** [`tasks-p4-overview.md`](./tasks-p4-overview.md) · [`plan.md`](./plan.md) §5a · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.7 (inbox endpoints, 12→5 status mapping, hypothesis-tree pane) · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §5 (NATS/Pythia runtime deps), §6.3 (investigation streams reattach via `/events?from_seq=N`) · [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) (`Investigation` summary, `InvestigationArtifact.hypotheses`, `HypStatus`, lifecycle subject — Pythia P1).

## Grounding

- **New BFF package** `inbox/` (architecture §3.1): aggregation + NATS subscriber. Nothing exists yet. Config: `iris.nats.{url, reconnect-max}`, `iris.inbox.poll-fallback-s` (default 30), `iris.pythia.{base-url, timeout-ms}` (contracts §6).
- **Status mapping (contracts §2.7):** Pythia's 12 states → 5 user-facing. `SUBMITTED`/`RESOLVING`/`PLANNING`/`EXECUTING` → **Running**; all five `AWAITING_*` (incl. `AWAITING_BUDGET_DECISION`) → **Needs your input**; `DONE` → **Done**; `FAILED` → **Failed**; `HALTED` → **Cancelled** (render partial conclusion). Enum names per `Pythia-v1-Design.md` §3.4 (the semantic authority).
- **Join source:** `iris_turns` already carries `origin` (`user|scheduled`), `origin_ref`, `session_id`, `question` — the inbox joins on the investigation's originating turn ref.
- **FE:** dockview is already in use (`stores/layoutStore.ts`, `tabsStore.ts`); the inbox is a new dockview panel + a header badge. Needs-input rows reuse the existing chat clarification UI; control calls proxy to Pythia per-state endpoints via the BFF. Hypothesis tree is a new debug-grade pane.
- **NATS client:** `io.nats:jnats`, pinned in `gradle/libs.versions.toml` as `nats = "0.6.2+3.5"` (Bora, 2026-06-23) — add the version entry + a `libs.nats` library alias and wire it into `agents/iris-bff/build.gradle.kts`. The subscriber is a singleton wired in `Wiring.kt`, non-gating for `/ready` (architecture §8).

## Pre-flight

- [ ] **Phase 3 closed**; Phase-4 pre-flight in [`tasks-p4-overview.md`](./tasks-p4-overview.md) satisfied.
- [ ] **Pythia `pythia/v0.1.0`** — `GET /v1/investigations?user_id=…` + `pythia.lifecycle.{user_id}` subject live; `InvestigationArtifact` shape (hypotheses, `parent_id`, `HypStatus`, confidence, `display_priority`, `test_step_ids`) frozen in `pythia/contracts.md`.
- [ ] NATS reachable from the BFF (`deployment/local` infra stage).
- [ ] Branch `feat/iris-p4-s4.1-inbox`.

## Tasks

- [ ] **T1 — Tests first: inbox aggregation spec (unit).** `InboxAggregator.build(userId): InboxView` joining a `FakePythiaClient` investigation list with in-memory `iris_turns`. Assert: the 12→5 status mapping (table-driven over all 12 Pythia states); `InboxItem` = investigation summary ⋈ `{session_id, session_title, turn_id, origin (USER|SCHEDULED)}`; `counts{running, needs_input}`; `HALTED` renders partial-conclusion flag; investigations with no matching `iris_turns` row (e.g. orphaned) handled gracefully; only `agent_kind = INVESTIGATOR` items included.
- [ ] **T2 — NATS lifecycle subscriber + polling fallback.** `inbox/LifecycleSubscriber` subscribing `pythia.lifecycle.{user_id}`, parsing lifecycle events, fanning out to connected `/v1/inbox/stream` SSE clients. When NATS is absent/down, degrade to polling `pythia GET /v1/investigations` every `iris.inbox.poll-fallback-s`; expose `iris_lifecycle_nats_connected_gauge` (0 → fallback active). **Tests first:** subscriber spec (fake NATS publisher → event fans out to a registered sink); fallback spec (NATS down → poller drives the same sink at cadence). Reconnect honours `reconnect-max`.
- [ ] **T3 — `GET /v1/inbox` + `GET /v1/inbox/stream` (contracts §2.7).** `/v1/inbox` → `{items:[InboxItem], counts:{running, needs_input}}` (bearer-auth, user-scoped via the forwarded bearer / `caller.userId`). `/v1/inbox/stream` → `text/event-stream` of `inbox_event` (the SSE machinery already exists — `stream/IrisSse.kt`, `respondSse`); subscribes the caller to T2's fan-out, seeds with the current aggregation. **Tests first:** `InboxRoutesSpec` (testApplication + FakePythia + fake NATS) — list shape + counts; stream emits an event on a fake lifecycle publish; user A cannot see user B's items.
- [ ] **T4 — FE: header badge + dockview inbox panel.** Header badge = Running + Needs-input counts (from `/v1/inbox`, live-updated via `/v1/inbox/stream`). A dockview `InboxPanel.vue`: rows of {question, status, elapsed, cost-so-far (`resource_usage`), session link}. Row click → open the session + reattach via Pythia `/events?from_seq=N` (replay-then-live bridge). Metrics surfaced: `iris_inbox_open_total`, `iris_inbox_reattach_total`. **Tests first:** inbox-store + panel component specs (badge counts, row render, click → open+reattach call); SSE update spec.
- [ ] **T5 — Needs-input rows → clarification UI + control proxy.** A **Needs your input** row lands the user on the pending clarification using the **existing chat clarification UI** (Stage 1.3 resume flow). Control actions (cancel / approve-budget / answer) call Pythia's per-state endpoints **proxied through the BFF** (so the bearer/OBO + audit hold). Cancel → `HALTED`; budget approve → `AWAITING_BUDGET_DECISION` resolution. **Tests first:** BFF proxy spec (control call forwarded with bearer, audited); FE spec (needs-input row → clarification UI populated; control buttons call the proxy).
- [ ] **T6 — Hypothesis-tree pane (debug-grade, PD-2).** A collapsible `HypothesisTree.vue` rendered from `InvestigationArtifact.hypotheses` (`parent_id` nesting; per node: statement, `HypStatus`, confidence, rationale, `display_priority`; expandable evidence links + a **flat `test_step_ids` step list** with status/cost — **no DAG pane**). Live-updates from the hypothesis/execution events on the investigation stream. Deliberately not product-polished — the debugging window into the investigator. **Tests first:** tree-build spec (parent_id nesting, display_priority ordering, flat step list per node); live-update spec (a streamed hypothesis event mutates the node).

## DONE

`just test-kt iris-bff` + FE vitest/tsc/lint green. Inbox aggregates Pythia state ⋈ `iris_turns`; NATS subscriber + polling fallback; `/v1/inbox(/stream)`; FE badge + panel with reattach; needs-input → clarification UI + control proxy; hypothesis tree renders + live-updates. Plan §9 Stage 4.1 ticked. Real-NATS reattach + live Pythia replay-then-live → integration suite.

## Out of scope (→ later stages)

- Pins/dashboards (PD-6) — **Stage 4.2**.
- Discovery / feedback / audit verify+retention (PD-7/PD-3/PD-8) — **Stage 4.3**.
- The plan-DAG pane (`kantheon-v1.1.md` §2) — steps stay flat lists per hypothesis.
