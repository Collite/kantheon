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

- [x] **T1 [K] — Vendor the DDL + the trailing-pipe load test (tests first).** DDL vendored to `deployment/tpcds/ddl/{tpcds.sql,tpcds_ri.sql}`. `TpcdsLoadComponentSpec` (workers/arges componentTest) runs the real `tpcds.sql` on Testcontainers PG, asserts the raw trailing-pipe `.dat` is rejected, then **strip-trailing-pipe + `COPY … WITH (DELIMITER '|', NULL '')`** loads a `reason` fixture with exact fidelity incl. empty-last → NULL. **Green** (1 test, 0 failures).
- [x] **T2 [O] — `test-pg` CNPG cluster + roles.** `platform/data/test-pg/base/{cluster.yaml (8Gi, roles tpcds+tpcds_readonly),databases.yaml (tpc-ds-1g)}` + `overlays/bp-dsk/{externalsecret-pg-tpcds-cred,externalsecret-pg-tpcds-ro-cred,kustomization}`; wired into `clusters/bp-dsk/platform/data` (the `data` app). Overlay builds clean.
- [~] **T3 [O] — Stage `.dat` to Seaweed — RUNBOOK (yours).** `tpcds-staging` bucket added to seaweed `createBuckets`. Upload steps (port-forward `seaweedfs-s3` + `aws s3 cp --no-sign-request`) in [`t1-tpcds-load.md`](./t1-tpcds-load.md) T3.
- [x] **T4 [O] — `tpcds-load` Job (uses the T1-proven load form).** `platform/data/test-pg/overlays/bp-dsk/load-job.yaml` (`postgres:16-alpine`+curl): DROP 25 tables → `tpcds.sql` → per-table `curl … | sed 's/|$//' | \copy … (DELIMITER '|', NULL '')` → `tpcds_ri.sql` + `ANALYZE` → grant `tpcds_readonly`. **NOT** auto-synced (excluded from the overlay kustomization) — applied manually so a sync never reloads 1.2 GB.
- [x] **T5 [O] — Run the load + verify — DONE (2026-07-06).** `tpcds-load` Job applied to bp-dsk → `Complete` (`succeeded=1`). Counts match the SF1 oracle exactly (store_sales 2,880,404 · catalog_sales 1,441,548 · web_sales 719,384 · customer 100,000 · date_dim 73,049 · item 18,000; 25 tables). `tpcds_readonly` verified read-only via `has_table_privilege` (SELECT=true, INSERT/UPDATE/DELETE=false, can-login). [`t1-tpcds-load.md`](./t1-tpcds-load.md) T5.
- [x] **T6 [K/O] — Idempotency + reload.** The Job drops the 25 tables first → delete+re-apply reloads to the same counts, no dup-key errors. Documented in the runbook.

## DONE

- [x] `TpcdsLoadComponentSpec` green locally (`just test-component`) — the trailing-pipe load form proven.
- [x] `test-pg`/`tpc-ds-1g` GitOps + load Job authored; **live load DONE (2026-07-06)** — vault keys materialized, olymp merged, data staged, T5 Job `Complete` with oracle-matching counts.
- [x] `tpcds_readonly` read-only — verified (`has_table_privilege`: SELECT-only); reload idempotency holds (T6: Job drops 25 tables first).

## Follow-ups → next stage

- **T2** — Ariadne TPC-DS model + curated queries + the `pg-tpcds` connection profile (Arges) + Kyklop routing; then the `tpcds-query` integration context (C2).
- Gated by **MP-1** for the live query path (Arges deployed on bp-dsk, D3) — but T1 itself only needs `test-pg` + the Job.
