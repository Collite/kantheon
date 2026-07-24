# Sysifos — v1 Design

> **🔄 SUPERSEDED-PENDING (FO-12, 2026-07-23).** Sysifos (the bespoke data-entry app) is superseded by
> **Studio Data Entry** (FO-P3) — the generic, md-derived entry surface over the one journal door (FO-8),
> with the investment book authored as the `kantheon/packages/investment` package rather than app-specific
> code. The Sysifos-DNA import UX (upload → diff preview → inline correction → commit) carries over as the
> §13 proposal-source parsers (`@investment/parsers`, e.g. the Conseq DistrInfo loader, ⚑R-2).
>
> **No rug-pull (FO-12).** *Pending*, not deleted: Sysifos runs until each estate migrates to Studio Data
> Entry — the cutover is the **LF-5 handoff** (Kantheon-side). Migration steps live with the Midas arc:
> [`../../architecture/midas/migration-to-investment-package.md`](../../architecture/midas/migration-to-investment-package.md).
>
> **Status.** Locked design from the 2026-06-13 brainstorm session. Six load-bearing decisions taken (S1–S6, §13). Supersedes the thin Sysifos treatment inside the Midas arc — Sysifos becomes its **own arc** (S1); the Midas arc retains Midas-core, the loaders, reports, Golem-Investment, and dashboards.
>
> **Reads with.** [`sysifos-brief.md`](./sysifos-brief.md) (the ask), [`sysifos-brainstorming.md`](./sysifos-brainstorming.md) (how we got here), [`../../architecture/midas/contracts.md`](../../architecture/midas/contracts.md) (the Midas-core REST + `midas/v1` contracts Sysifos consumes — cited by section, never duplicated), [`../iris/iris-design.md`](../iris/iris-design.md) (the sibling surface this one is deliberately *not* shaped like).
>
> **Source of truth boundary.** This doc owns the *design rationale*. The wire contracts (`sysifos/v1`, Sysifos-BFF API) move to `docs/architecture/sysifos/contracts.md` when the arc trio is written; Midas-core's contracts stay in the Midas arc and are referenced. One source of truth per boundary.

---

## 1. Vision

Sysifos is the forms-shaped half of the Midas product: a rich web workbench where a back-office user keeps a book of clients, portfolios, assets, and transactions correct. It optimises for **speed of correct entry** and **auditability**, not for analysis. Every change it makes flows through Midas-core's append-only write API, so the book is always reconstructable and every edit is logged.

Three properties define it:

1. **Propose, don't write.** Sysifos never mutates the operational DB. It assembles a proposal (a manual transaction, a balance target, an import commit) and sends it to Midas-core, which derives, validates, and commits. Results come back for display.
2. **Derivation over reconstruction.** The user states intent at the highest convenient level — a target balance, a security leg without the cash side — and the system derives the rest. Less typing, fewer errors.
3. **Idempotent, reversible, audited.** Imports de-dup on `external_id`. Edits never overwrite — they reverse-and-replace. Every write lands in `audit_log`.

## 2. Physical composition

Two deployables, mirroring Iris's split:

- **`agents/sysifos-bff`** — Kotlin + Ktor. Auth, tenant forwarding, form orchestration, the hybrid write model (§7), read fan-out/assembly, SSE for long ops. No business logic.
- **`frontends/sysifos`** — Vue 3 + TypeScript + PrimeVue 4 (Aura) + Pinia + TanStack Query + vue-router. Forms, grids, import wizard, validation.

Shared with Iris-BFF via `shared/libs/kotlin/bff-base`: Keycloak JWT verification, `X-Tenant-Id` forwarding, envelope-render reuse, capabilities-client wrappers. (`bff-base` extraction is triggered here; if the shared surface is < ~200 LOC, fold into both BFFs instead — same hedge as Midas plan.)

## 3. Responsibilities

### What Sysifos owns

- **Master-data CRUD UX** — clients, portfolios, asset dictionary (asset writes gated to `midas:admin`).
- **Manual transaction entry** — single-record form + bulk grid (S3, §5).
- **Balance entry** — target-state → derived `ADJUSTMENT` preview + commit (§5.3).
- **Statement import UX** — upload → preview-with-diff → inline correction → commit (§6).
- **Reconciliation UX** — diff display + per-diff decision (§6.4).
- **Loader status + audit viewers.**
- **Three-layer client-side validation** and optimistic-feeling edits (§7).
- **The asset quick-create modal** invoked mid-entry on unknown symbols (S6, §5.4).

### What Sysifos does NOT own

- **The book.** Midas-core is the only writer. Sysifos has no DB of record; its only persistent state is session + draft scratch (and even that can be ephemeral in v1 — §7).
- **Cash-leg derivation, balance-diff derivation, reverse-and-replace.** These live in Midas-core (`derivation/`). Sysifos sends the security leg / target / edit and renders what comes back.
- **Calc.** TWR/MWR/FIFO/valuation are Midas-core MCP tools consumed by Iris/Golem, not Sysifos.
- **Charts and narrative.** Iris owns analytics (Midas D6). Sysifos shows tables.
- **Parsing broker statements.** The Midas Excel loader parses/maps/commits; Sysifos drives its lifecycle and renders previews.

## 4. The data-entry model

### 4.1 Two levels of input (from the brief)

| Level | User states | System derives | Result |
|---|---|---|---|
| **Transaction** | a buy/sell/dividend/fee (security leg) | the **cash counter-leg** (S2) | 2 rows: security + cash |
| **Balance** | "position should be N as of D" | the **`ADJUSTMENT`** that closes the gap | 1 row (or 0 if already at target) |

Both go to Midas-core. Both are append-only. Both are previewable before commit.

### 4.2 Derived cash legs (S2 — a Midas-core amendment Sysifos consumes)

The single biggest manual-input decision: a trade has a cash side, and the user shouldn't have to type it. When Sysifos posts a `BUY` of N shares @ price P with fee F in currency C, **Midas-core derives a cash leg**: a transaction against the portfolio's cash asset in C, of kind `TX_CASH_DEBIT`, amount `−(N·P + F)`. A `SELL` derives a `TX_CASH_CREDIT`; dividends/interest credit cash; fees/taxes debit it.

This requires a **Midas-arc amendment** (tracked as a dependency, not owned here):

- `midas/v1.TransactionKind` gains `TX_CASH_CREDIT` and `TX_CASH_DEBIT`.
- A cash asset (`AssetKind.ASSET_CASH`) per `(portfolio, currency)` is auto-provisioned by Midas-core on first need.
- New `midas-core/derivation/CashLegDerivation.kt` emits the counter-leg inside the same commit as the security leg; both share a correlation so a reversal of one reverses both.
- A **per-portfolio `track_cash` toggle** (new field on `Portfolio`): when off, no cash leg is derived (for books where cash is managed elsewhere). Default on.

Sysifos's role: render *both* legs in the post-commit confirmation and in the Transactions grid (cash legs shown as a linked sub-row), and expose the `track_cash` toggle on the Portfolio form. It sends only the security leg.

### 4.3 Why append-only matters to the UX

Because edits reverse-and-replace rather than overwrite, the Transactions screen must *show* that history honestly: an edited row is dimmed with a "Reversed" badge and its replacement appears as a new row; the reversing entry is visible (collapsible). This is a deliberate UX choice — the user sees the true event log, not a mutated illusion. Reconciliation and audit depend on it.

## 5. Manual entry surfaces

### 5.1 Single-record form

The default for one-off entries. PrimeVue reactive form bound to a `sysifos/v1.TransactionForm`; inline validation from a Zod schema generated off the proto (§7). Asset and portfolio are typeaheads against the BFF dictionary cache. Submit posts synchronously (hybrid model, S5) and the confirmation shows the security + derived cash legs.

### 5.2 Bulk grid (S3)

A spreadsheet-style grid for heavy entry days — the difference between a usable back-office tool and a toy. Properties:

- **Paste-friendly** — paste a block from Excel/clipboard; columns map to `TransactionForm` fields by header or position.
- **Tab-through entry** — keyboard-first; Enter adds a row, Tab moves cell-to-cell.
- **Per-cell inline validation** — same Zod rules as the form; bad cells flagged live, not on submit.
- **Unknown symbols** trigger the quick-create modal (§5.4) without losing grid state.
- **Async commit** — the grid is a *long op*: the whole block posts as a batch via a Draft + SSE progress (S5), each row committed through Midas-core's `POST /transactions:batch`. Per-row results (committed / failed) stream back into the grid as status pills.

The grid and the form write through the same BFF dispatch; the grid is the bulk path, the form the single path. No third entry concept.

### 5.3 Balance entry

Pick portfolio → asset → target quantity → as-of date → **Preview**. The preview calls Midas-core `POST /balance-entries:preview` (read-only) and renders the proposed `ADJUSTMENT` with a plain-language explanation ("Current 100 → Target 120 → Adjustment +20 AAPL"). A separate **Commit** re-runs the diff server-side (race-safe) and inserts. A History tab lists prior balance entries (`kind = ADJUSTMENT`, `source = DERIVATION`).

### 5.4 Asset quick-create modal (S6)

When manual entry or an import row references a symbol not in the dictionary, Sysifos opens an **inline modal** to create the asset with minimal fields (symbol, name, kind, currency; ISIN/exchange optional) before continuing. This keeps master data clean (no silent stubs) while not forcing a context-switch to the Assets screen. The modal writes via Midas-core `POST /assets`; on success the new asset drops back into the originating cell/field and entry resumes. In the bulk grid and import preview, multiple unknown symbols queue into one modal pass.

## 6. Import (S4 — broker templates in v1)

### 6.1 Flow

Sysifos owns the *interaction*; the Midas Excel loader owns the *pipeline*. The screen sequence:

```
Upload            Preview                         Correct                Commit
┌──────────┐     ┌──────────────────────────┐   ┌─────────────────┐   ┌──────────────┐
│ pick file│ →   │ rows: NEW / DUPLICATE /   │ → │ fix ERROR rows  │ → │ batch commit │
│ broker +  │     │ ERROR  + diff vs system   │   │ quick-create    │   │ via loader → │
│ portfolio │     │ (SSE progress while       │   │ unknown assets  │   │ midas-core   │
└──────────┘     │  parsing)                 │   │ exclude rows    │   └──────────────┘
                 └──────────────────────────┘   └─────────────────┘
```

- **Upload** — PrimeVue FileUpload (chunked for large XLSX). User picks broker template + target portfolio. POST to the loader via BFF proxy returns a `loader_run_id`.
- **Preview** — SSE `LoaderProgress` drives a progress bar; on `LoaderPreviewReady` the preview table renders `PreviewRow`s grouped by decision (NEW / DUPLICATE / ERROR) with a per-row diff against existing transactions.
- **Correct** — ERROR rows (unmapped symbol, unparseable date, missing field) are fixable inline: unknown symbol → quick-create modal (§5.4); other errors → editable cell or exclude-row checkbox. This is the Sysifos-specific value-add over a raw loader.
- **Commit** — posts a `DRAFT_LOADER_RUN_COMMIT`; the loader proxies a batch insert to Midas-core (idempotent on `external_id`). Success navigates to the Transactions grid filtered to the just-imported rows.

### 6.2 What's deferred to v1.x

- **Interactive column-mapping** for ad-hoc Excel that doesn't match a known broker template. v1 requires a predefined template. The seam: the import screen already has a "broker template" selector; v1.x adds a "custom mapping" option that opens a mapping builder and saves the result as a reusable named template.
- Non-Excel sources (Google Finance, API, SFTP, Yahoo) — later loaders; they appear only in loader-status.

### 6.3 Idempotency in the UX

Re-uploading the same statement is safe by design: the loader assigns `external_id = {broker}:{statement_id}:{line_no}`, duplicates show as DUPLICATE in preview, and commit skips them. The UI states this plainly ("12 new, 340 already imported") so the user trusts re-runs.

### 6.4 Reconciliation

After a completed run, the user can invoke reconcile for the portfolio/period: Midas-core `POST /reconcile` returns `ReconcileDiff`s (system-only / statement-only / value-mismatch). The Reconcile screen groups them and posts a per-diff decision (`DRAFT_RECONCILIATION_DECISION`) — expected / investigate / resolved — persisted so future runs don't re-prompt resolved diffs.

## 7. Write model + validation topology

### 7.1 Hybrid write model (S5)

| Path | Mechanism | Why |
|---|---|---|
| Single-record writes (client, portfolio, one transaction, balance commit, asset quick-create) | **synchronous** request/response | low latency already; optimistic ceremony adds failure modes for no felt gain |
| Long ops (bulk grid commit, statement import, large reconcile) | **async** `Draft` + **SSE** progress | real multi-second latency; the user needs progress + per-row outcomes |

The `sysifos/v1.Draft` / `SysifosStreamEvent` types stay in the contract as the async seam; they're exercised by the long ops, dormant for the simple ones. This is the smallest model that keeps simple paths simple and gives the heavy paths honest progress.

### 7.2 Three-layer validation

Validation is authoritative in exactly one place but echoed for UX:

1. **FE (Zod, generated from `sysifos/v1` + `midas/v1`)** — instant inline feedback (required fields, types, `trade_date <= today`, positive quantities). No round-trip.
2. **BFF pre-flight** — echoes Midas-core's documented rules (the same constraints) so a malformed draft is rejected before it reaches core; also cross-field checks needing dictionaries (asset exists, portfolio active).
3. **Midas-core** — the authority. RLS, idempotency, CHECK constraints, derivation invariants. Its rejection (`FieldValidationError`s) is the truth; FE/BFF rules are a *cache* of it.

The drift risk (three copies of the rules) is managed by generating layers 1–2 from the proto + a small shared rule manifest, never hand-maintaining them independently. Where a rule can't be expressed declaratively, core is authoritative and the FE simply surfaces core's error.

## 8. Screens (v1)

| Screen | Route | Core surface used | Notes |
|---|---|---|---|
| Clients | `/clients` | Midas `/clients` | CRUD + archive |
| Portfolios | `/portfolios` | Midas `/portfolios` | CRUD + archive; `track_cash` toggle; base-ccy |
| Assets | `/assets` | Midas `/assets` | read for all; write gated `midas:admin`; quick-create modal source |
| Transactions | `/transactions` | Midas `/transactions` | virtualized grid; form + bulk grid; inline edit (reverse+replace); cash sub-rows |
| Balance entry | `/balance` | Midas `/balance-entries:*` | preview + commit + history |
| Import | `/import` | loader proxy | upload → preview → correct → commit |
| Reconcile | `/reconcile` | Midas `/reconcile` | diff + per-diff decision |
| Loader status | `/loaders` | loader `/runs` | all loaders; admin trigger for pollers |
| Audit | `/audit` | Midas `audit_log` read | admin only; before/after diff; Grafana trace link-out |

No dashboards in Sysifos (Iris owns them).

## 9. Module map

### 9.1 `agents/sysifos-bff/`

```
src/main/kotlin/org/tatrman/kantheon/sysifos/bff/
├── App.kt                       # Ktor bootstrap (≤45 lines)
├── auth/                        # Keycloak JWT (via bff-base)
├── tenant/                      # X-Tenant-Id forward (via bff-base)
├── api/
│   ├── SessionRoute.kt
│   ├── DraftRoute.kt            # async write seam (bulk/import)
│   ├── CrudProxyRoute.kt        # sync read/write proxies to midas-core
│   ├── BalanceRoute.kt          # preview/commit proxy
│   ├── LoaderProxyRoute.kt      # excel loader lifecycle proxy
│   ├── ReconcileRoute.kt
│   └── DictionaryRoute.kt       # brokers / currencies / tx-kinds (cached)
├── stream/StreamRoute.kt        # SSE: SysifosStreamEvent
├── write/                       # hybrid dispatch: sync vs draft+SSE
├── session/                     # SysifosSession + draft scratch
└── midas/MidasCoreClient.kt     # Ktor HttpClient to midas-core REST
```

### 9.2 `frontends/sysifos/`

```
src/
├── router/                      # vue-router; one route per §8 screen
├── views/                       # Clients, Portfolios, Assets, Transactions,
│                                #   BalanceEntry, Import, ImportPreview,
│                                #   Reconcile, Loaders, Audit
├── components/
│   ├── forms/                   # PrimeVue reactive forms (Zod-validated)
│   ├── grids/                   # virtualized DataTables + the bulk-entry grid
│   ├── import/                  # upload, preview, inline-correction
│   └── AssetQuickCreate.vue     # the mid-entry modal
├── stores/                      # Pinia: session, drafts, dictionaries, tenant
├── api/                         # generated TS clients (sysifos/v1, midas/v1, envelope-ts)
├── validation/                  # Zod schemas generated from proto + rule manifest
└── i18n/                        # cs / en
```

## 10. Arc restructuring (consequence of S1)

Sysifos as its own arc means the Midas arc sheds its Sysifos-specific parts and Sysifos picks them up:

| Concern | Before (Midas arc) | After |
|---|---|---|
| `sysifos/v1` proto | Midas contracts §1.2 | **Sysifos arc** contracts |
| Sysifos-BFF API | Midas contracts §5 | **Sysifos arc** contracts |
| Sysifos-BFF + FE build/deploy | Midas plan P1 S1.6 | **Sysifos arc** plan |
| Sysifos screens | Midas plan P2 (all stages) | **Sysifos arc** plan |
| `midas/v1`, Midas-core REST/MCP, DDL | Midas contracts §1.1/§2/§3/§6 | **stays in Midas arc**; Sysifos references by section |
| Excel loader service | Midas arc | **stays in Midas arc** (writer-adjacent; broker-format-coupled). Sysifos owns only the import *screens* |
| Cash-leg derivation + `TX_CASH_*` + `track_cash` | — (new) | **Midas arc amendment**, consumed by Sysifos |

**Dependency direction:** Sysifos arc depends on Midas-core (Midas P1 S1.3/1.4) and the cash-leg amendment landing first. Sysifos's own Phase 1 (BFF + FE shell) can start once Midas-core's write API answers; Sysifos Phase 2 (the screens) tracks Midas-core endpoint readiness per screen.

**De-duplication rule:** the Sysifos contracts doc *cites* Midas contract sections; it never restates a `midas/v1` message or a Midas-core endpoint. If a Sysifos screen needs a core capability that doesn't exist, that's a Midas-arc change request, made in the Midas docs first.

## 11. Security

Inherited from the Midas security model — no new surface:

- Keycloak JWT on every BFF request; `midas:read` / `midas:write` / `midas:admin` roles. Assets-write, audit viewer, and poller-trigger gated to `admin`.
- `tenant_id` claim → BFF forwards `X-Tenant-Id` → Midas-core sets `app.tenant_id` → DB RLS enforces. Sysifos never sees another tenant's rows.
- Client PII (names, contacts) redacted from OTel span attrs (tenant_id only).

## 12. Open items

- **`bff-base` extraction trigger** — decide at Sysifos Phase 1 whether the Iris-BFF/Sysifos-BFF shared surface justifies the lib or folds into both (same hedge as Midas plan).
- **Draft scratch persistence** — are in-flight bulk-grid drafts durable across a refresh, or session-memory only in v1? Leaning session-memory; durable drafts v1.x.
- **Cash asset identity** — confirm one cash asset per `(portfolio, currency)` vs one per `(tenant, currency)` shared across portfolios. Affects the Midas-arc amendment; leaning per-portfolio for clean RLS + per-book cash positions.
- **Bulk-grid column-mapping on paste** — header-based vs positional default; how much smart-mapping in v1.
- **Reconcile granularity** — diff at transaction level only in v1, or also position/balance level. Brief implies transaction-level; confirm.
- **i18n scope** — cs + en confirmed; decimal/date locale formatting in the grid needs an explicit rule.

## 13. Resolved decisions — quick reference

| # | Decision | Locked | Notes |
|---|---|---|---|
| **S1** | Sysifos is its **own arc** (architecture + contracts + plan), split out of the Midas arc; references Midas contracts, never duplicates | 2026-06-13 | §10 restructuring; Midas keeps core/loaders/reports/Golem/dashboards |
| **S2** | Trade **cash leg derived in Midas-core** via new `TX_CASH_CREDIT`/`TX_CASH_DEBIT`; per-portfolio `track_cash` toggle; auto-provisioned cash asset | 2026-06-13 | Midas-arc amendment; Sysifos sends security leg only, renders both |
| **S3** | Manual entry = **single-record form + spreadsheet-style bulk grid** | 2026-06-13 | Grid is the bulk async path (§5.2) |
| **S4** | Import = **predefined broker templates in v1**; interactive column-mapping → v1.x | 2026-06-13 | Sysifos owns the upload→preview→correct→commit UX; loader owns the pipeline |
| **S5** | **Hybrid write model** — sync for single records, async `Draft`+SSE for bulk grid + import | 2026-06-13 | Draft/SSE types stay as the async seam |
| **S6** | Unknown asset symbol → **inline quick-create modal** mid-entry | 2026-06-13 | No silent stubs; no forced context-switch |

---

*Design doc owner: Bora. Lives in `docs/design/sysifos/`. The build artefacts (architecture / contracts / plan) follow once this design is approved; they live under `docs/architecture/sysifos/` and `docs/implementation/v1/sysifos/`. Revision history via git.*
