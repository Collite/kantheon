# Sysifos — Phased Implementation Plan (kantheon arc)

> **Scope.** From "kantheon has no Sysifos modules" to "Sysifos workbench live in local K3s — a back-office user can do a full day of operational data entry, import, and reconciliation." Two phases, nine stages, ~60 tasks. Split out of the Midas arc per decision **S1** (2026-06-13).
>
> **Companions.** [`../../../architecture/sysifos/architecture.md`](../../../architecture/sysifos/architecture.md), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md), [`../../../design/sysifos/sysifos-design.md`](../../../design/sysifos/sysifos-design.md), [`../../planning-conventions.md`](../../planning-conventions.md), [`../midas/plan.md`](../midas/plan.md) (the arc Sysifos depends on).
>
> **Hierarchy** (per `planning-conventions.md`): task → stage (~6 tasks, testable) → phase (set of stages, deployable).
>
> **Status (2026-06-24): P1–P2 CODE-COMPLETE (#48, reviewed).** All nine stages (1.1–1.3, 2.1–2.6) are merged with mocked specs green — bff edge, shell+draft, sync CRUD + cash sub-rows, bulk grid, statement import, balance entry, reconcile + loader status + audit. **The one remaining step is a Stream-T stage: Testing Stage 3.4** (Sysifos GitOps deploy + layered workbench-smoke on bp-dsk), which cuts the tags `sysifos-bff/v0.1.0` + `sysifos/v0.1.0` + `sysifos-arc/phase-2-data-entry-v1`. Pre-flight for that: live Midas-core + Excel loader on bp-dsk + the `sysifos-bff` Keycloak service account. *(Earlier status — "unblocked, start Stage 1.1" — is superseded; engineering is done.)*

---

## 1. Phase summary

| Phase | Goal — what deploys at the end | Stages | Estimated effort |
|---|---|---|---|
| **Phase 1 — Foundation** | `agents/sysifos-bff` running in local K3s with Keycloak auth, tenant forwarding, session + draft + stream + dictionary surfaces, and a Midas-core client; `frontends/sysifos` shell renders nav + login + placeholder routes; `sysifos/v1` proto published; `bff-base` extracted (or folded); hybrid write model skeletoned. | 1.1 / 1.2 / 1.3 | ~3 weeks |
| **Phase 2 — Data entry** | Every v1 screen live end-to-end against Midas-core: Clients, Portfolios (`track_cash`), Assets + quick-create, Transactions (form + inline edit + cash sub-rows), bulk grid, Balance entry, Import (upload→preview→correct→commit), Reconcile, Loader status, Audit. | 2.1 / 2.2 / 2.3 / 2.4 / 2.5 / 2.6 | ~5–6 weeks |

**Total:** ~8–9 weeks. Critical path Phase 1 → Phase 2. Within Phase 2, screen stages can partly parallelise if developers split, but the conservative default is strict sequence; 2.2/2.3 gate on the Midas cash-leg amendment.

---

## 2. Pre-flight — what must be true before Phase 1 starts

| Pre-flight item | Source | Required by | Status (2026-06-23) |
|---|---|---|---|
| Midas-core write API live (clients/portfolios/assets/transactions/balance) | Midas arc P1 S1.3 | Sysifos P1 S1.3 (draft commit flow) | **MET** (Midas S1.3 done) |
| Midas-core deployed + reachable by Service in local K3s | Midas arc P1 S1.3 | Sysifos P1 S1.2 | **MET** (deployed on bp-dsk) |
| Excel loader lifecycle live | Midas arc P1 S1.5 | Sysifos P2 S2.5 (import) | **MET** (Midas S1.5 done) |
| **Derived cash-leg behaviour** (`TX_CASH_*`, `track_cash`, `CashLegDerivation`, cash-asset provisioning) live in Midas-core — baseline, built in Midas Stage 1.3 | Midas baseline (S2) | Sysifos P2 S2.2 (cash sub-rows) + S2.3 (bulk grid) | **MET** (Midas S1.3 T7) |
| `tools/capabilities-mcp` running (for `bff-base` capabilities wrappers) | Themis arc P1 | Sysifos P1 S1.2 | **MET** (capabilities-mcp live) |
| Iris-BFF skeleton exists (for `bff-base` extraction audit) | Iris arc | Sysifos P1 S1.2 (audit; fold-or-extract decision) | **MET** (iris-bff live on bp-dsk; audit against its `auth/`+`tenant/`) |
| Keycloak realm + service account for `sysifos-bff` | `infra/whois` | Sysifos P1 S1.2 | **OPEN — config item** (only outstanding pre-flight; not needed for S1.1) |
| `sysifos/v1` proto relocated from the Midas arc listing | Midas contracts §1.2 → Sysifos contracts §1 | Sysifos P1 S1.1 | ready (S1.1 task) |

**Midas-core's cash-leg derivation is the one hard external gate inside Phase 2.** It's baseline Midas behaviour (built in Midas Stage 1.3), not a separate amendment — but Sysifos still can't render cash sub-rows until Midas-core derives them. Sysifos Phase 1 and Stage 2.1 do not need it; Stages 2.2 and 2.3 do. If Midas-core slips, run 2.1, 2.4 (balance), and 2.5 (import — minus cash rendering) first and defer 2.2/2.3.

---

## 3. Phase 1 — Foundation

**Goal.** Bring the Sysifos shell to life: a deployable BFF with auth/tenant/session/draft/stream/dictionaries and a Midas-core client, and an FE shell with nav, login, and placeholder routes. Everything below the business screens.

**Deployable at phase close.** `sysifos-bff` + `frontends/sysifos` pods running in local K3s; a logged-in user sees the nav shell, tenant name, and every placeholder route; `/stream` emits a heartbeat; a `DRAFT_CLIENT` posted via the draft path round-trips to Midas-core and back.

### Stage 1.1 — Arc bootstrap + `sysifos/v1` proto relocation

**Goal.** Module skeletons exist; `sysifos/v1` compiles to Kotlin + TS in its new home; build green on stubs.

**Pre-flight.** Midas arc P1 S1.2 done (so `midas/v1` exists to import).

**Tasks (6).**
1. **Module skeletons** — create `agents/sysifos-bff/` (Kotlin module layout + `k8s/{base,overlays/local}/`) and `frontends/sysifos/` (Vite + Vue + `package.json`). Empty stubs that compile.
2. **Relocate `sysifos/v1`** — move the proto from the Midas arc's listing to `shared/proto/.../sysifos/v1/sysifos.proto` per [`contracts.md`](../../../architecture/sysifos/contracts.md) §1, including the four Sysifos-arc additions (`DRAFT_TRANSACTION_BATCH`, `DRAFT_ASSET`, `BatchRowResult`, `AssetForm`/`TransactionBatchForm`/`ReconciliationDecisionForm`, `track_cash`).
3. **`libs.versions.toml`** — add nothing DB-related; confirm Ktor/Kotlin/coroutine/serialization versions; add FE deps (PrimeVue 4, TanStack Query, Pinia, Zod, vue-router) to the FE `package.json`.
4. **Settings + root build** — add the two modules to `settings.gradle.kts`; apply the kotlin-ktor convention to `sysifos-bff`; exclude the FE from the Kotlin convention but wire it into `justfile`.
5. **Justfile + CI** — `just sysifos-dev`, `just build-fe sysifos`, `just build-kt sysifos-bff`, `just deploy-kt sysifos-bff`; CI learns both modules (auto-detect).
6. **Proto-shape test** — Kotest spec verifying `sysifos/v1` round-trips and `track_cash` defaults true; `just proto` idempotent.

**DONE.** `just build-kt sysifos-bff` green on stubs; `just proto` regenerates `sysifos/v1` Kotlin + TS; CI passes. Tag `sysifos/bootstrap-v0.1.0`.

### Stage 1.2 — `bff-base` + Sysifos-BFF skeleton

**Goal.** BFF authenticates JWTs, forwards `X-Tenant-Id`, serves session/dictionaries/health/ready, and reaches Midas-core. `bff-base` extracted or folded per audit.

**Pre-flight.** Stage 1.1 done; Keycloak service account; Iris-BFF available for the extraction audit.

**Tasks (7).**
1. **`bff-base` extraction audit** — measure the genuinely-shared surface vs Iris-BFF; decide extract-lib vs fold (threshold ~200 LOC). Record the decision in the stage task list.
2. **`bff-base`** (or folded helpers) — `auth/KeycloakJwtVerifier`, `tenant/TenantHeaderForwarder`, envelope-render reuse, capabilities-client wrappers; tests against fixture JWTs.
3. **BFF skeleton tests first** — Ktor TestApplication: `/sessions/current` requires JWT; mismatch → 401; tenant header forwarded; `/health` 200, `/ready` reflects Midas-core reachability.
4. **BFF skeleton** — `App.kt` (EXAMPLES.md §1b), `auth/`, `api/SessionRoute`, `api/DictionaryRoute`, `midas/MidasCoreClient` (Ktor HttpClient), `/health` + `/ready`.
5. **Dictionaries** — brokers (from loader registry stub), currencies, transaction-kinds, asset-kinds with cs/en labels; TTL cache; tests.
6. **Stream skeleton** — `stream/StreamRoute` SSE emitting `SessionHeartbeat` every 30s; test the channel opens + heartbeats.
7. **Deploy + smoke → Testing Stage 3.4** (BFF-edge leg) — Jib + Kustomize; curl `/sessions/current` with a real Keycloak JWT; `/ready` 200 when Midas-core up.

**DONE.** `sysifos-bff` deployable; auth + tenant + session + dictionaries + heartbeat stream all answer; `bff-base` decision recorded.

### Stage 1.3 — Hybrid write skeleton + FE shell

**Goal.** The draft path commits a single `DRAFT_CLIENT` end-to-end to Midas-core (proving the async machinery), and the FE shell renders all routes behind login.

**Pre-flight.** Stage 1.2 done; Midas-core write API live (Midas P1 S1.3).

**Tasks (7).**
1. **Write-dispatcher tests first** — `WriteDispatcher` routes single records to the sync proxy and batch/import drafts to the async path; `DraftStateMachine` PENDING→COMMITTING→COMMITTED|REJECTED.
2. **Sync CRUD proxy** — `api/CrudProxyRoute` forwarding reads + single writes to Midas-core with `X-Tenant-Id` (per contracts §3.3); component test with Wiremock'd Midas-core.
3. **Draft path (single)** — `api/DraftRoute` + `DraftStateMachine`: POST `DRAFT_CLIENT` → `DraftAck` → Midas-core `POST /clients` → `DraftCommitted` via SSE; `DraftRejected` with `FieldValidationError`s on failure. Tests first.
4. **FE scaffolding** — Vite + Vue 3 + PrimeVue (Aura) + Pinia + vue-router; generated TS clients (`sysifos/v1`, `midas/v1`, `envelope-ts`); Zod codegen from `validation-rules.yaml`.
5. **Auth + nav shell** — `/login` (Keycloak redirect), nav, tenant switcher (if >1 tenant); Pinia session store; route guard.
6. **Placeholder routes** — empty `views/` for Clients, Portfolios, Assets, Transactions, BalanceEntry, Import, Reconcile, Loaders, Audit; SSE client subscribing to `/stream`.
7. **Deploy + smoke → Testing Stage 3.4** (shell + async-draft leg) — nginx FE + Jib BFF; log in, see nav + tenant, navigate every placeholder; post a client via the draft path from a dev console → appears in Midas-core.

**DONE.** Shell live; a `DRAFT_CLIENT` round-trips through the async path; all routes render empty behind login. Tag `sysifos-arc/phase-1-foundation-v1`.

### Phase 1 closing

**Phase 1 DONE.** All three stage DONE criteria met. Demo: log in, see the shell, post a client through the draft path, watch the `DraftCommitted` SSE event, confirm in Midas-core.

---

## 4. Phase 2 — Data entry

**Goal.** Every v1 screen end-to-end. By close, Sysifos is operationally complete from the user's perspective.

**Deployable at phase close.** All ten surfaces (§8 of architecture) live; a full operational day works in the UI.

### Stage 2.1 — Clients + Portfolios screens

**Goal.** Full CRUD for Clients and Portfolios incl. the `track_cash` toggle. List, create, edit, archive; Zod validation; optimistic-feeling sync writes.

**Pre-flight.** Phase 1 closed.

**Tasks (6).**
1. **Clients screen tests first (Vitest)** — render, fill, submit (sync), success, list refresh.
2. **Clients screen** — `views/Clients.vue` + `ClientForm` + `ClientList` (PrimeVue DataTable, server-side filter/sort/page; TanStack Query reads; sync write via CrudProxy).
3. **Portfolios tests first** — per-client filter; base-ccy dropdown; **`track_cash` toggle**; FIFO badge.
4. **Portfolios screen** — `views/Portfolios.vue` + form + list; `track_cash` defaults on, surfaced with helptext ("derive the cash side of each trade").
5. **Archive UX** — soft archive with confirm dialog; archived badge; include-archived toggle.
6. **Dictionaries + i18n** — currency dictionary in Pinia; cs/en strings for all labels.

**DONE.** Create+edit+archive a client and a portfolio (with `track_cash`); sync writes feel instant.

### Stage 2.2 — Assets + quick-create modal + Transactions screen

**Goal.** Asset master (read all, write admin) + the S6 quick-create modal; Transactions list with inline edit (reverse+replace) and **derived cash sub-rows** (needs the Midas amendment).

**Pre-flight.** Stage 2.1; **Midas-core derives cash legs (baseline, Midas Stage 1.3).**

**Tasks (7).**
1. **Assets screen tests first** — list, filter by symbol/kind/exchange; admin create/edit.
2. **Assets screen** — `views/Assets.vue`; read-only for non-admin; `midas:admin` sees the form.
3. **Quick-create modal tests first** — invoked from a transaction asset field with an unknown symbol; minimal fields; on success the new asset returns to the originating field; grid/import state preserved.
4. **`AssetQuickCreate.vue`** — modal posting `DRAFT_ASSET` (or sync `POST /midas/assets`); `AssetForm` Zod; queue multiple unknowns into one pass.
5. **Transactions list tests first** — list per portfolio; filter date/kind/asset; virtual scroll >1000 rows; **cash sub-row grouping** under each security leg.
6. **Transactions list + cash sub-rows** — virtualized DataTable; `/screens/transactions` fan-out; cash legs (`TX_CASH_*`, `source=DERIVATION`) rendered as linked, dimmed sub-rows.
7. **Single manual entry + inline edit** — "Add transaction" form (sync, security leg only; confirmation shows both legs); pencil-edit → Midas-core PATCH → reversal+new; original dimmed "Reversed", replacement appears.

**DONE.** All single-transaction CRUD paths work; reversals visibly correct; cash sub-rows render for `track_cash` portfolios.

### Stage 2.3 — Bulk grid (S3)

**Goal.** Spreadsheet-style bulk entry: paste a block of trades, tab-through, per-cell validation, async batch commit with per-row results.

**Pre-flight.** Stage 2.2; Midas-core derives cash legs (baseline; batch derives cash legs too).

**Tasks (7).**
1. **Grid lib decision** — PrimeVue editable DataTable vs a dedicated grid; spike paste + tab-through ergonomics; record the call.
2. **Grid component tests first (Vitest)** — paste a 20-row block; header/positional column mapping; per-cell Zod validation flags bad cells live.
3. **Bulk grid component** — `components/grids/BulkEntryGrid.vue`; portfolio-scoped; paste, keyboard nav, add/remove rows.
4. **Unknown-symbol path** — a grid cell with an unknown symbol opens the quick-create modal without losing grid state; multiple unknowns queue.
5. **Async commit tests first** — submit grid → `DRAFT_TRANSACTION_BATCH` → `DraftAck` → per-row `BatchRowResult` stream → `DraftCommitted` with counts; partial failures surface per row.
6. **Async commit** — `DraftStateMachine` drives `POST /transactions:batch` (skip_existing); stream `BatchRowResult` into the grid as status pills; failed rows stay editable.
7. **Deploy + smoke → Testing Stage 3.4** (bulk-grid leg) — paste a real block, commit, watch per-row pills, confirm in Transactions list (with cash sub-rows).

**DONE.** A block of trades commits via the async path; per-row outcomes stream back; failures are correctable in place.

### Stage 2.4 — Balance entry screen

**Goal.** "Set position X to Q as of D" → preview derived `ADJUSTMENT` → commit, with a visible explanation.

**Pre-flight.** Stage 2.2 (Transactions list to view the result).

**Tasks (5).**
1. **Preview tests first** — fixture 100 AAPL, target 120 → +20 ADJUSTMENT; target 80 → −20; target == current → friendly no-op.
2. **Preview flow** — `views/BalanceEntry.vue`: portfolio → asset → target qty → as-of → Preview (sync `balance-entries:preview`); render proposed txn + plain-language explanation.
3. **Commit flow** — separate Commit; `balance-entries:commit` re-runs the diff server-side (race-safe) then inserts.
4. **Error UX** — no-diff message; invalid portfolio/asset → field validation.
5. **History tab** — prior balance entries (`kind=ADJUSTMENT`, `source=DERIVATION`) for the portfolio/asset.

**DONE.** Balance entry round-trips; derived `ADJUSTMENT` visible in Transactions.

### Stage 2.5 — Statement import end-to-end

**Goal.** Excel loader's upload→preview→commit becomes a real Sysifos flow with **inline correction** of ERROR rows and the quick-create modal for unknown symbols.

**Pre-flight.** Stage 2.2 (quick-create modal); Excel loader live (Midas P1 S1.5).

**Tasks (7).**
1. **Upload tests first** — file picker → broker selector → portfolio selector → upload → SSE progress → preview ready.
2. **Upload screen** — `views/Import.vue` + PrimeVue FileUpload (chunked); proxy `POST /loaders/excel/uploads`; SSE `LoaderProgress` drives a progress bar; navigate to preview on `LoaderPreviewReady`.
3. **Preview tests first** — fixture with N new + M duplicate + K error rows; grouped by decision; per-row include/exclude.
4. **Preview view** — `views/ImportPreview.vue` via `/screens/import/{id}` fan-out; DataTable of `PreviewRow`s (NEW/DUPLICATE/ERROR badges, proposed-txn summary, diff note).
5. **Inline correction** — ERROR rows fixable: unknown symbol → quick-create modal; bad date/field → editable cell; exclude-row checkbox. The Sysifos-specific value-add.
6. **Commit flow** — `DRAFT_LOADER_RUN_COMMIT`; loader → Midas-core batch (idempotent); success → Transactions filtered to imported rows; "12 new, 340 already imported" messaging.
7. **History tab (in-arc)** — past runs per portfolio; click → read-only preview. **Deploy + smoke → Testing Stage 3.4** (import leg) — smoke both fixture brokers; re-upload skips duplicates.

**DONE.** Full import path works for both fixture brokers; ERROR rows correctable inline; re-runs idempotent.

### Stage 2.6 — Reconcile + Loader status + Audit

**Goal.** Reconciliation diff + per-diff decision; loader run overview; audit viewer.

**Pre-flight.** Stage 2.5.

**Tasks (7).**
1. **Reconcile tests first** — `POST /reconcile` fixture: 3 system-only, 2 statement-only, 1 value-mismatch; grouped.
2. **Reconcile screen** — `views/Reconcile.vue`: select portfolio + loader_run + period; section per diff kind; per-diff decision dropdown → `DRAFT_RECONCILIATION_DECISION`.
3. **Decision persistence + summary** — decisions persist (don't re-prompt resolved); top-of-page summary (total/open/by-status); "show only open" filter.
4. **Loader status screen** — `views/Loaders.vue`: last-50 runs across loaders; status pills; run-details modal (rows summary, error_summary, links to preview/transactions); admin "trigger" for pollers.
5. **Audit viewer tests first** — admin-only; filter by entity_type/actor/time; before/after JSON diff.
6. **Audit viewer** — `views/Audit.vue` (admin); side-by-side JSON diff (jsondiffpatch); Grafana trace link-out via `trace_id`.
7. **Phase 2 deploy + smoke → Testing Stage 3.4** (full round-trip leg) — fresh tenant full round-trip: client → portfolio → import → edit → bulk grid → balance → reconcile → audit. The arc tag `sysifos-arc/phase-2-data-entry-v1` lands with Testing 3.4 T6.

**DONE.** Reconcile + loader status + audit live; full operational round-trip works.

### Phase 2 closing

**Phase 2 DONE.** Demo: create a tenant from scratch, run a full operational day (clients, portfolios, single + bulk entry with cash legs, statement import with corrections, balance entry, reconcile, audit review). Sysifos operationally complete. Tag `sysifos-arc/phase-2-data-entry-v1`. Arc complete.

---

## 5. Risks and known unknowns

- ~~**Cash-leg amendment slippage** (Midas arc) — gates 2.2/2.3.~~ **Resolved** — cash-leg derivation shipped as Midas baseline (S1.3 T7, 2026-06-21); 2.2/2.3 are unblocked.
- **`bff-base` premature extraction** — if shared surface < ~200 LOC, fold into both BFFs (Stage 1.2 audit).
- **Bulk-grid paste ergonomics** — PrimeVue's editable DataTable may not give clean Excel-paste/tab-through; Stage 2.3 T1 spikes a dedicated grid as fallback.
- **SSE reliability behind Ingress** — long-lived `/stream` connections through Traefik; verify keep-alive + reconnect in Stage 1.2; fall back to polling for progress if needed.
- **Draft scratch loss on refresh** — in-memory in v1; a mid-grid refresh loses the draft. Documented; durable drafts v1.x.
- **Three-layer validation drift** — mitigated by generating layers 1–2 from `validation-rules.yaml`; never hand-maintain.

---

## 6. Out of scope for this arc

- Interactive column-mapping for ad-hoc Excel (S4 → v1.x).
- Durable cross-refresh drafts; offline entry.
- Charts / analytics (Iris).
- Direct DB access (Midas-core only).
- Non-Excel loaders' entry UX (they appear in loader-status only).
- Per-portfolio ACLs; finer-grained roles than read/write/admin.
- Full E2E integration testing — separate flow per convention. Per the testing policy (planning-conventions.md §4): plans develop against mocked unit tests only (Kotest + Wiremock for the Midas-core/JWKS edges); real-integration and e2e verification live in the **Testing arc**. The per-stage **"deploy + smoke" tasks were moved out of this arc to Testing Stage 3.4** ([`../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md`](../testing/tasks-p3-s3.4-sysifos-deploy-smoke.md), 2026-06-24) — they are GitOps deploy + live cluster confirmations on bp-dsk, not in-arc tasks and not an automated e2e gate. Each "deploy + smoke" line above is retained as a pointer to its leg of Testing 3.4 T5.

---

## 7. Sequencing summary

```
Phase 1 ───────────────────────►  Phase 2 ──────────────────────────────────────────►
  1.1 → 1.2 → 1.3                  2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6

  Midas-arc gates (all MET 2026-06-21 — Midas Phase 1 done):
    Midas-core write API ─────────► needed at 1.3   ✓
    Excel loader ─────────────────► needed at 2.5   ✓
    cash-leg derivation (baseline)► needed at 2.2/2.3 ✓
```

Critical path: 1.1 → 1.2 → 1.3 → 2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6. All Midas-arc gates are now met, so the arc can run straight through; the earlier "if the cash-leg amendment slips, reorder to 2.1 → 2.4 → 2.5 → (2.2 → 2.3 later)" contingency no longer applies.

---

*Plan doc owner: Bora. Lives in `docs/implementation/v1/sysifos/`. Update on every scope, deliverable, or sequencing change. Revision history via git.*
