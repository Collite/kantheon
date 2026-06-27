# WS-T Stage 1 — `test-pg` server + `tpc-ds-1g` database + load Job

> **Workstream T (TPC-DS), Stage 1.** Stand up the dedicated `test-pg` Postgres server on bp-dsk with the `tpc-ds-1g` database, loaded from the SF1 `.dat` files via a Seaweed-staged, trailing-pipe-safe `COPY` Job. T2 then puts an Ariadne model + curated queries + the `pg-tpcds` connection over it.
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §4 (TPC-DS contracts), [`master-plan.md`](./master-plan.md) §7-D1.
> **Data (host).** `~/Data/TPC-DS` — 25 `.dat` (pipe-delimited, **trailing `|`**, SF1 ~1.2 GB) + `tpcds.sql` (25 `CREATE TABLE`s, use) + `tpcds_ri.sql` (FKs, use) + `tpcds_source.sql` (`s_*` ETL — **not** used). Repos: **[O]** olymp (server+Job) + **[K]** kantheon (DDL vendoring + the load test).

## Goal

`test-pg` (CNPG, separate from the shared agent PG) runs on bp-dsk; `tpc-ds-1g` is loaded with all 24 tables (FKs + `ANALYZE`); `tpcds_readonly` can `SELECT` everything; a re-run is idempotent.

## Pre-flight

- [ ] Seaweed (S3) live on bp-dsk (platform/data/seaweed — ✓).
- [ ] CNPG operator live (platform/data/postgres operator — ✓).
- [ ] Branch `feat/t1-test-pg-load` in **both** repos.

## Tasks

- [ ] **T1 [K] — Vendor the DDL + the trailing-pipe load test (tests first).** Copy `tpcds.sql` + `tpcds_ri.sql` into `deployment/tpcds/ddl/`. Write a **component test** `TpcdsLoadComponentSpec` (`component-testkit` `Containers.postgres()`): create the schema from `tpcds.sql`, then load a **tiny fixture `.dat`** (3–4 rows, with the trailing `|`) for one small table (e.g. `reason` or `income_band`) via the chosen trailing-pipe-safe `COPY` form (contracts §4.2 — dummy-trailing-column **or** strip-trailing-pipe); assert exact row + column-value fidelity. This proves the load form **before** the cluster Job uses it.
- [ ] **T2 [O] — `test-pg` CNPG cluster + roles.** `platform/data/test-pg/base/{cluster.yaml,databases.yaml}` + `overlays/bp-dsk/` per contracts §4.1: `Cluster test-pg` (instances 1, storage 8Gi), managed roles `tpcds` (owner) + `tpcds_readonly`, `Database tpc-ds-1g`, `ExternalSecret`s `pg-tpcds-cred` / `pg-tpcds-ro-cred`. Wire into the bp-dsk app-of-apps (own Application or under the data appset).
- [ ] **T3 [O] — Stage `.dat` to Seaweed.** A documented one-time upload of `~/Data/TPC-DS/*.dat` → Seaweed bucket `tpcds-staging` (`mc cp`/`aws s3 cp` against the in-cluster Seaweed S3 endpoint). Record the bucket/prefix; reloads never need host access again.
- [ ] **T4 [O] — `tpcds-load` Job (uses the T1-proven load form).** `platform/data/test-pg/load-job.yaml` — a `Job` in ns `data`: (1) `psql -f tpcds.sql` (idempotent `DROP/CREATE` or `TRUNCATE`); (2) for each of the 24 tables, fetch `<table>.dat` from Seaweed → trailing-pipe-safe `COPY`; (3) `psql -f tpcds_ri.sql` + `ANALYZE`; (4) grant `tpcds_readonly` (contracts §4.1). Image: a small `postgres`+`mc` client. Mount creds from `pg-tpcds-cred`.
- [ ] **T5 [O] — Run the load on bp-dsk + verify.** Apply the Job to bp-dsk; wait `Complete`; verify row counts against the known SF1 cardinalities (e.g. `store_sales` ≈ 2.88M, `date_dim` = 73049, `customer` = 100000) — a checked-in `expected-counts.txt` + a `psql` count check. Confirm `tpcds_readonly` can `SELECT` but not write.
- [ ] **T6 [K/O] — Idempotency + reload.** Re-run the Job → same counts, no duplicate-key errors (truncate-reload path). Document `just` helper or runbook for "reload tpc-ds-1g".

## DONE

- [ ] `TpcdsLoadComponentSpec` green locally (`just test-component`) — the trailing-pipe load form proven.
- [ ] `test-pg`/`tpc-ds-1g` live on bp-dsk; `tpcds-load` Job `Complete`; row counts match SF1 expected.
- [ ] `tpcds_readonly` read-only access confirmed; reload is idempotent.

## Follow-ups → next stage

- **T2** — Ariadne TPC-DS model + curated queries + the `pg-tpcds` connection profile (Arges) + Kyklop routing; then the `tpcds-query` integration context (C2).
- Gated by **MP-1** for the live query path (Arges deployed on bp-dsk, D3) — but T1 itself only needs `test-pg` + the Job.
