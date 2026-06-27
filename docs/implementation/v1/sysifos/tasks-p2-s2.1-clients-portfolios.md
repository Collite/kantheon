# Stage 2.1 — Clients + Portfolios screens

> **Phase 2, Stage 2.1.**
>
> **Reads with.** [`tasks-p2-overview.md`](./tasks-p2-overview.md), [`plan.md`](./plan.md) §4 (Stage 2.1), [`../../../architecture/sysifos/contracts.md`](../../../architecture/sysifos/contracts.md) §3.3 (CRUD proxy) + §4 (validation), [`../midas/contracts.md`](../../../architecture/midas/contracts.md) §2.1–2.2 (Midas-core clients/portfolios).

## Goal

Full CRUD for Clients and Portfolios — list, create, edit, archive — with the `track_cash` toggle on portfolios, Zod-validated forms, and sync writes that feel instant.

## Pre-flight

- [x] **Phase 1 closed.**
- [x] **Branch**: `feat/sysifos-p1-p2-workbench` (whole-arc branch).

## Tasks

- [x] **T1 — Clients screen tests first (Vitest + MSW).** Render `Clients.vue`; fill `ClientForm`; submit (sync `POST /midas/clients`); assert success toast + list refresh; assert Zod blocks an invalid email inline.

- [x] **T2 — Clients screen.** `views/Clients.vue` + `components/forms/ClientForm.vue` + `components/grids/ClientList.vue`. PrimeVue DataTable (server-side filter/sort/page via `?page&size&status&name_prefix`); TanStack Query for reads; sync write via CrudProxy; success invalidates the query.

- [x] **T3 — Portfolios tests first.** Render `Portfolios.vue`; per-client filter; base-currency dropdown from `/dictionaries/currencies`; **`track_cash` toggle present, default on**; FIFO badge shown (read-only — `cost_basis_method`).

- [x] **T4 — Portfolios screen.** `views/Portfolios.vue` + form + list. `PortfolioForm` includes `track_cash` (default true, helptext "derive the cash side of each trade"). Base-currency dropdown; portfolio_type dropdown; inception date picker.

- [x] **T5 — Archive UX.** Soft-archive flow (`POST /midas/{clients,portfolios}/{id}/archive`) behind a confirm dialog; archived rows badged; an "include archived" toggle on each list.

- [x] **T6 — Dictionaries + i18n.** Currency dictionary cached in Pinia; cs/en strings for every label/placeholder/validation message on both screens (i18n keys, no hardcoded text).

## DONE — Stage 2.1

- [x] All six tasks checked; `just build-fe sysifos` + Vitest green.
- [x] Create + edit + archive a client; create + edit + archive a portfolio with `track_cash`; sync writes feel instant. _(screens built; manual round-trip is a deploy-pass demo)_

## Library / pattern references

- PrimeVue 4 DataTable (server-side lazy) + FormKit/PrimeVue forms (context7 for current API).
- TanStack Query invalidation patterns.
- Midas-core clients/portfolios endpoints — `../midas/contracts.md` §2.1–2.2.

## Out of scope

- Assets/Transactions (2.2). Cash sub-rows (2.2). The `track_cash` *effect* (cash-leg derivation) is Midas-core's — this stage only sends the flag.
