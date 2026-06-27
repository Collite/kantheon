# Stage 1.3 — Hybrid write skeleton + FE shell

> **Phase 1, Stage 1.3.**
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 (Stage 1.3), [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md) §6 (hybrid write model), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §3 (BFF API), EXAMPLES.md §11 (Vue + TS bindings).

## Goal

The async draft path commits a single `DRAFT_CLIENT` end-to-end to Midas-core (proving the machinery); the sync CRUD proxy forwards reads/single-writes; the FE shell renders all routes behind login.

## Pre-flight

- [x] **Stage 1.2 DONE.**
- [x] **Midas-core write API live** (Midas P1 S1.3) — met (Midas Phase 1 done 2026-06-21); reachability is a deploy-pass concern.
- [x] **Branch**: `feat/sysifos-p1-p2-workbench` (whole-arc branch).

## Tasks

- [x] **T1 — Write-dispatcher + state-machine tests first.** `src/test/kotlin/.../write/`:
  ```kotlin
  "WriteDispatcher routes single-record DraftKind to sync proxy path"
  "WriteDispatcher routes DRAFT_TRANSACTION_BATCH + DRAFT_LOADER_RUN_COMMIT to async path"
  "DraftStateMachine: PENDING -> COMMITTING -> COMMITTED emits DraftAck then DraftCommitted"
  "DraftStateMachine: midas-core 4xx -> REJECTED emits DraftRejected with FieldValidationErrors"
  ```
  Acceptance: specs written, failing.

- [x] **T2 — Sync CRUD proxy.** `api/CrudProxyRoute.kt` per contracts §3.3 — forwards `/midas/*` reads + single writes to Midas-core via `MidasCoreClient`, injecting `X-Tenant-Id`. Component test (Wiremock'd Midas-core): GET `/midas/clients` → forwards with tenant header → returns body; POST `/midas/clients` (sync write) → 201 passthrough.

  Acceptance: proxy forwards + injects tenant; tests green.

- [x] **T3 — Draft path (single record).** `api/DraftRoute.kt` + `write/DraftStateMachine.kt` + `session/DraftScratch.kt` (in-memory). Flow for `DRAFT_CLIENT`: POST `/drafts` → 202 `{draft_id, PENDING}` → emit `DraftAck` on `/stream` → map `payload_json` → `ClientForm` → Midas-core `POST /clients` → on 201 emit `DraftCommitted`; on 4xx emit `DraftRejected` with mapped `FieldValidationError`s. Make T1 specs pass.

  Acceptance: `DRAFT_CLIENT` round-trips; SSE events emitted; tests green.

- [x] **T4 — FE scaffolding.** `frontends/sysifos`: wire Vue 3 + PrimeVue (Aura preset) + Pinia + vue-router + TanStack Query in `main.ts`. Generate TS clients from `sysifos/v1`, `midas/v1`, and `envelope-ts`. Add a Zod-codegen step reading `validation-rules.yaml` (contracts §4) into `src/validation/*.ts`. EXAMPLES.md §11 for envelope-ts consumption.

  Acceptance: `just sysifos-dev` serves; generated clients + Zod schemas present.

- [x] **T5 — Auth + nav shell.** `/login` (Keycloak redirect/PKCE); Pinia `session` store (user, tenant, roles from JWT); route guard redirecting unauthenticated to `/login`; nav shell (PrimeVue menu) + tenant switcher shown when the user has >1 tenant.

  Acceptance: login round-trips; nav renders; tenant name shown.

- [x] **T6 — Placeholder routes + SSE client.** `views/` empty pages for Clients, Portfolios, Assets, Transactions, BalanceEntry, Import, Reconcile, Loaders, Audit; `router/` one route each. A composable `useSysifosStream()` subscribing to `/stream`, parsing `SysifosStreamEvent`, dispatching to Pinia (heartbeat updates last-seen; draft events update a drafts store). Vitest: the composable handles a `DraftCommitted` event.

  Acceptance: every route renders empty behind login; stream composable tested.

- [→] **T7 — Deploy + smoke → MOVED to Testing Stage 3.4.** The cluster-tier deploy + live smoke for the shell + async-draft path (log in → nav + tenant → navigate every route → POST a `DRAFT_CLIENT` → observe `DraftCommitted` on the stream → confirm via `GET /midas/clients`) is owned by the Testing arc — see [`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md) (T5 "Shell + async draft" leg). **In-arc deliverables done:** FE container artifacts authored — `Dockerfile` (nginx:alpine), `nginx.conf.template` (listen 7602, same-origin `/bff` proxy → sysifos-bff:7601, SSE-tuned), `scripts/{generate-env,nginx-entrypoint}.sh`, `public/env.js` — so `just publish-fe-image sysifos` is ready (T1 of 3.4).

## DONE — Stage 1.3

- [x] T1–T6 done; T7 deploy+smoke **moved to Testing Stage 3.4** (no longer an in-arc task).
- [x] `just test-kt sysifos-bff` (20 specs) + `just build-fe sysifos` (build + 6 vitest specs + lint) green. _(Cluster smoke owned by Testing 3.4.)_
- [x] `DRAFT_CLIENT` round-trips via the async path (DraftRouteSpec, Wiremock); all routes render behind the auth guard.
- [ ] Tag `sysifos-arc/phase-1-foundation-v1`. _(Deferred to post-merge — whole arc ships in one PR.)_

## Library / pattern references

- EXAMPLES.md §11 — Vue FE consuming envelope/TS bindings.
- EXAMPLES.md §9 — Wiremock for the stubbed Midas-core in component tests.
- TanStack Query + Pinia docs (context7 if needed) — store vs server-cache split.

## Out of scope

- Real forms/grids/import (Phase 2). The bulk-grid + import async flows reuse this draft machinery but land in 2.3/2.5.
