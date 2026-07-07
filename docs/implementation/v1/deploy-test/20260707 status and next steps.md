# Deploy-Test — Status & Next Steps (2026-07-07 session handoff)

> **What this is.** A point-in-time status snapshot of the deploy-test program (`master-plan.md` +
> `tasks-overview.md`), cross-checked against what is **actually live on bp-dsk** (not just the docs),
> written to hand off into a fresh session. Read with [`master-plan.md`](./master-plan.md),
> [`tasks-overview.md`](./tasks-overview.md), and [`deployments.md`](./deployments.md) (the live bring-up log).
>
> *Session 2026-07-07. Owner: Bora.*

---

## Milestones

| | Milestone | Status |
|---|---|---|
| **MP-1** | Query path live on bp-dsk (theseus→proteus→argos→kyklop→**arges**) | ✅ **done** — all 23 constellation pods READY |
| **MP-2** | TPC-DS queryable (manual `theseus query` returns rows) | ✅ **done live 2026-07-07** — all 4 curated queries |
| **MP-3** | Component test matrix green in CI | ⬜ not started |
| **MP-4** | Integration run-set green on bp-dsk + release tags cut | ⬜ not started |

---

## What's DONE

**WS-D (deployment)**
- **D1** shared Helm library chart (`shared/charts/kantheon-service`) — done (2026-07-05)
- **D2** 22 charts + images — done (2026-07-05)
- **D3 waves 1–3** — **live & healthy on bp-dsk** (23 apps, single `kantheon` ns):
  - registry/core: `capabilities-mcp, ariadne, prometheus, echo, kadmos` (+ `*-mcp` wrappers)
  - full query path: `proteus, argos, kyklop, theseus` (+ `theseus-mcp`), workers `brontes, arges, steropes`
  - plus `charon, metis` (+ mcps), and the original 4: `iris, iris-bff, golem`

**WS-T (TPC-DS)**
- **T1** `test-pg` CNPG server + `tpc-ds-1g` loaded — done (2026-07-06). SF1 counts match the oracle
  (store_sales 2,880,404 · catalog_sales 1,441,548 · web_sales 719,384 · customer 100,000 ·
  date_dim 73,049 · item 18,000; 25 tables); `tpcds_readonly` verified SELECT-only.
- **T2** Ariadne model + `tpcds` area + 4 curated queries + `pg-tpcds` connection + Kyklop routing
  (T1–T7) — done (2026-07-07), incl. the **live MP-2 proof** and the ttr-translator **0.8.5**
  namespace fix (see "Notable this session").

### MP-2 live result (2026-07-07)
Full orchestrated path `theseus.Run → proteus (parse) → argos (validate) → kyklop (dispatch) →
arges (unparse+execute) → Postgres tpc-ds-1g` as `tpcds_readonly` — all four curated shapes return rows:

| Query | Shape | Rows |
|---|---|---|
| Q1 store_sales_by_month | join + group-by | 12 |
| Q2 top_items_by_revenue | join + agg + ORDER/LIMIT | 30 |
| Q3 customer_running_total | **window (OVER/PARTITION)** | 30 |
| Q4 channel_revenue_cte | **CTE + UNION ALL** | 3 |

---

## What's REMAINING

**WS-D3 waves 4–7** (`D3 T4–T6`) — the rest of the estate is **not** deployed:
- wave 4 agents: `themis`, `pythia`
- wave 5 domain: `midas-core`, `midas-excel-loader`, `report-renderer`, `sysifos-bff`, `frontends/sysifos`
- wave 6 librarian: `hebe`, `kleio`, `kallimachos`, `pinakes`, `kallimachos-mcp`
- wave 7 infra: `whois`, `health`, `frontends/landing` (in scope); `backstage`, `kallimachos-browse` (best-effort)
- plus **D3 T7** — estate smokes + `chartRevision`→`master` flips on merge

**WS-C (tests)** — both unstarted:
- **C1** component-tier real-dep matrix — 0/13 (Testcontainers, **no cluster needed**) → closes **MP-3**
- **C2** integration contexts incl. **`tpcds-query`** — 0/14

**WS-R** — unstarted:
- **R1** `infra-up --kube dsk` run mode + ArgoCD reconcile-boundary verify — 0/13

**Release tags** — deferred; cut together at **MP-4** (contracts §9).

---

## Recommended next steps (in order)

1. **R1 — bp-dsk run mode** (0/13, olymp-heavy). The hard pre-task: make `infra-up --kube dsk` work
   and prove the appset reconcile boundary doesn't capture `test-contexts/` or run namespaces.
   Unblocks *every* on-cluster integration run.
2. **C2 `tpcds-query` context** — turn today's manual smoke into a repeatable `RunQueryIntegrationSpec`
   + olymp `test-contexts/tpcds-query/`. The convergence demo of the MP-2 proof; lands **MP-4's**
   showcase. Depends on R1.
3. **C1 component matrix** (0/13) — run **in parallel starting now**, no cluster. Closes **MP-3**.
   Good background track while R1/C2 need the cluster.
4. **D3 waves 4–7** — stand up the rest of the estate. Needed for "full constellation live" (program
   DONE) and for the domain/UX contexts (`midas-investment`, `iris-session`, `sysifos-workbench`).
5. **Finish C2's other contexts** — re-enable `theseus-runquery` result/RLS asserts; `golem-erp`,
   `themis-routing`, `pythia-rca`, + the domain contexts from step 4.
6. **MP-4 close** — full run-set green via `--kube dsk`, then cut the deferred v1 release tags.

**Critical chain to "program DONE":** **R1 → C2 → (D3 waves 4–7 for domain contexts) → MP-4**, with
**C1 running in parallel** throughout (needs nothing from the cluster).

---

## Notable this session (context the next session needs)

- **Whole query-path estate was rebuilt `:testing` from master** (ariadne, proteus, argos, theseus,
  arges, kyklop) — the previously-running pods predated the WS-T2 + Phase-B work. All `:testing`,
  `pullPolicy: Always`.
- **olymp wiring landed** (bp-dsk): Arges dropped fixture mode + real `pg-tpcds` env
  (`clusters/bp-dsk/apps/arges/values.yaml`); `pg-tpcds-ro-cred` materialized into the `kantheon` ns
  via `clusters/bp-dsk/platform/auth/clusterexternalsecret-pg-tpcds-ro.yaml`. **Brontes stays in
  fixture** (no MSSQL target; TPC-DS never routes to it) — a deliberate deviation from the doc wording.
- **Kyklop** chart default already wires `KYKLOP_WORKER_ARGES_ENDPOINT=arges:7303` — no olymp change needed.
- **ttr-translator 0.8.5** (tatrman `kotlin-translator/v0.8.5`): the `RelToSqlUnparser` schema-strip is
  now dialect-aware — Postgres/DuckDB drop the logical `dbo` namespace to the bare table name (resolves
  via `search_path=public`); MSSQL keeps `dbo`. This closed the `dbo.store_sales does not exist` gap.
  An Arges-layer fix was attempted first and **reverted** (the translator re-resolves the model by
  namespace during unparse, so the plan namespace can't be rewritten worker-side).
  - **v1 caveat tracked:** the namespace-drop is unconditional for Postgres/DuckDB (fine for the single
    logical-namespace v1 model; would need to become schema-aware for a multi-schema Postgres model).
    Filed as **Collite/tatrman#17**.
- **Manual live-query recipe** (for re-verifying MP-2 next session): port-forward `svc/theseus 7306`,
  then `grpcurl -plaintext -import-path shared/proto/build/extracted-include-protos/componentTest
  -proto org/tatrman/theseus/v1/theseus.proto -d '{"source":"<SQL>","source_language":"SQL",
  "source_schema":"DB"}' localhost:7306 org.tatrman.theseus.v1.TheseusService/Run` (reflection is off).
- **Operational boundaries hit repeatedly (hand these to Bora):** shared-cluster `kubectl` mutations
  (`rollout restart`), pushes to default branches, and prod-GitOps (olymp) pushes are gated by the auto-mode
  classifier — the next session will need explicit approval or `!`-prefix execution for these.

---

*Created 2026-07-07 as a session handoff. Owner: Bora. Next session: start with R1 (unblocks most) or C1 (no dependencies).*
