# Iris Phase 4 Stage 4.2 — artifacts: pins & dashboards

> **Goal (plan §5a Stage 4.2).** A **pin** is a saved, refreshable view of any envelope (snapshot + `ViewProvenance` + producing `agent_id` + `applied_context` + BFF display-state slice). A **dashboard** is a named collection of pins + `layout_json` (+ optional `template_id`). The BFF persists them in `iris_artifacts`, refreshes them **deterministically (never an LLM call)**, and the FE renders pin actions + a dashboard view. **Pin → dashboard → reopen → refresh round-trip green; Midas Stage 3.5 consumable.**
>
> **Companions.** [`tasks-p4-overview.md`](./tasks-p4-overview.md) · [`plan.md`](./plan.md) §5a · [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §2.8 (artifact endpoints + Midas reframe), §3.3 (`iris_artifacts` DDL) · [`../../../architecture/iris/architecture.md`](../../../architecture/iris/architecture.md) §3.1 (`artifacts/` package), §6.1 (`current_display` vs `current_view`) · [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §7 (the design this supersedes — Midas becomes a consumer).

## Grounding

- **New BFF package** `artifacts/` (architecture §3.1): repository + refresh orchestration. Nothing exists yet.
- **DDL (contracts §3.3):** `iris_artifacts(artifact_id, user_id, tenant_id, kind[pin|dashboard], name, agent_id, envelope_json, provenance, applied_context, display_state, params_json, refresh_mode[manual|on_open], param_mode[moving|frozen], template_id, member_ids[], layout_json, refreshed_at, refresh_error, …)`. New Flyway migration in `agents/iris-bff/src/main/resources/db/migration/` — use the **next unused `Vn` integer after the current head at the time this stage runs** (`V1__iris_core.sql` is the Phase-1 head; Phase 3 Stage 3.1 adds `V2__iris_turns_alternates.sql`; so this is `V3__iris_artifacts.sql` unless another migration already claimed `V3`, in which case the next free integer). Run `ls agents/iris-bff/src/main/resources/db/migration/` first and pick the next integer — no other judgement needed.
- **Refresh semantics (contracts §2.8):** **Golem-kind** pins → producing agent's typed-action surface (the Stage 3.2 `/v1/action` re-issue) then re-apply `display_state`; **Pythia-kind** pins → `replay` (`param_mode=moving`) or `reproduce` (`param_mode=frozen`). Failure → explicit `refresh_error` / stale state on the pin — **never silently wrong**. `refresh_mode: manual | on_open` in v1 (`scheduled` = v1.1 / Hebe). Refresh runs under the **owner's OBO** token. **No Pythia dependency for Golem-kind pins** — those are testable now.
- **Capture assembly** reuses Phase-3 machinery: the turn's terminal envelope (`iris_turns.envelope_json`), `ViewProvenance` (from `TurnPointer.current_view`, populated in 3.2 T4), `applied_context`, and the `current_display` slice.
- **FE:** pin action on table/chart bubbles (new affordance on `ChatBubble`); a dashboard view (new route/panel) with layout + template support; tiles render `refreshed_at` + PD-9 provenance ⓘ + PD-4 scope indicator.

## Pre-flight

- [ ] **Phase 3 closed** (the typed-action re-issue surface that Golem-kind refresh rides on; `current_view`/`applied_context` populated on turns by 3.2 T4).
- [ ] Branch `feat/iris-p4-s4.2-artifacts`.
- [ ] Pythia `replay`/`reproduce` endpoints frozen in `pythia/contracts.md` — needed **only** for Pythia-kind pin refresh (the Golem-kind path has no Pythia dep; Pythia-kind refresh tests use a Wiremock/Fake Pythia).

## Tasks

- [ ] **T1 — Tests first: pin capture + refresh specs (unit).** `ArtifactService` against in-memory repo + Fake producing agent / Fake Pythia. Assert: capture assembles {envelope snapshot, `ViewProvenance`, `agent_id`, `applied_context`, `display_state`} from a fixture turn; **Golem-kind refresh** = typed-action re-issue + re-apply `display_state` → fresh `envelope_json` + `refreshed_at`; **Pythia-kind refresh** = `replay` (moving) vs `reproduce` (frozen) per `param_mode`; **failure** → `refresh_error` set + stale state retained, **never** a silently-wrong envelope.
- [ ] **T2 — `iris_artifacts` migration + repository.** The Flyway migration named per Grounding above (next unused `Vn`, expected `V3__iris_artifacts.sql`), with the contracts §3.3 DDL verbatim. `ArtifactRepository` on both stores (InMemory + Exposed) — CRUD + list-by-kind + member management for dashboards. App role: INSERT/SELECT/UPDATE/DELETE on `iris_artifacts`. **Tests first:** repository spec (InMemory) — create pin, create dashboard with `member_ids`, list filtered by kind, update layout/params, delete.
- [ ] **T3 — `POST/GET/PATCH/DELETE /v1/artifacts` + pin-capture (contracts §2.8).** `POST /v1/artifacts {turn_id, bubble_id, name}` → BFF assembles the capture from the turn it owns. `GET` (kind filter), `GET/PATCH/DELETE /v1/artifacts/{id}` (PATCH: rename, edit `params_json` — "same chart, Q3", layout). Owner-checked; refs stable + unguessable (UUID, PD-15). **Tests first:** `ArtifactRoutesSpec` (testApplication) — pin from turn, list, patch params, delete; non-owner → 404; capture carries provenance + applied_context + display_state.
- [ ] **T4 — `POST /v1/artifacts/{id}/refresh` + `GET /v1/dashboards/{id}/open` (SSE).** Single-pin refresh (deterministic per T1 semantics, **never an LLM call**, owner-OBO). Dashboard open = parallel per-pin refresh honouring `refresh_mode` (`on_open` refreshes; `manual` serves last snapshot), streaming per-pin `envelope`/`error` SSE events. **Tests first:** refresh route spec (Golem-kind ok; failure → `refresh_error` event, pin marked stale); dashboard-open spec (parallel per-pin events, `manual` pins served from snapshot, `on_open` pins refreshed).
- [ ] **T5 — FE: pin action + dashboard view.** Pin affordance on table/chart bubbles (capture name → `POST /v1/artifacts`). A dashboard view: layout (`layout_json`), template support (`template_id` + `params_json` — e.g. Midas `investment-overview:v1`), tiles rendering `refreshed_at` + PD-9 provenance ⓘ popover (SQL in a collapsed expander) + PD-4 scope indicator; per-tile + whole-dashboard refresh. **Tests first:** pin-action component spec; dashboard-view component spec (tiles render provenance/scope/refreshed-at; refresh triggers the SSE consumer; stale/error tile state shown).
- [ ] **T6 — Audit `artifact_refresh` + metrics + component pass.** Audit each refresh as `event_kind: artifact_refresh` (contracts §3.1) when data access occurs. Metrics: `iris_artifact_refresh_total{kind=pin|dashboard, result}`. testApplication component coverage: pin → add to dashboard → open (refresh) → reopen round-trip; refresh failure → stale state + audit row. Green: `just test-kt iris-bff` + ktlint; FE vitest + vue-tsc + build + lint.

## DONE

`just test-kt iris-bff` + FE vitest/tsc/lint green. Pin → dashboard → reopen → refresh round-trip green; Golem-kind pins fully exercised (no Pythia dep); Pythia-kind refresh against Fake Pythia; failures surface explicit stale/error state; `artifact_refresh` audited; metrics emitted. **Midas Stage 3.5 consumable** (this generic system supersedes the Midas dashboard design per contracts §2.8). Plan §9 Stage 4.2 ticked. Live Pythia replay/reproduce + real-cluster dashboard open → integration suite.

## Out of scope (→ later stages / other arcs)

- `scheduled` refresh mode → v1.1 as a Hebe routine kind `artifact_refresh` (Hebe owns scheduling + bound-user OBO).
- Midas domain templates + Golem-Investment content — Midas Phase 3 Stage 3.5 (consumer of this system).
- Discovery / feedback / audit verify+retention — **Stage 4.3**.
