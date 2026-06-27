# WS-C Stage 2 — Integration contexts (incl. `tpcds-query`) + run-set

> **Workstream C (Test suite), Stage 2.** Build the integration-tier contexts: the new **`tpcds-query`** showcase (Goals 2+4), finish the existing draft contexts, and assemble the run-set that runs green via `infra-up --kube dsk` (**MP-4**).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §6 (integration contract) + §7 (bp-dsk), [`tasks-r1-bp-dsk-run-mode.md`](./tasks-r1-bp-dsk-run-mode.md), [`../testing/plan.md`](../testing/plan.md) §3.1 (existing contexts).
> **Reference.** olymp `test-contexts/theseus-runquery/` (the live, complete example) + `golem-erp/` (draft). kantheon `RunQueryIntegrationSpec` + `@RequiresContext` + `ContextNameRegistrySpec`. Repos: **[K]** kantheon specs+fixtures · **[O]** olymp `test-contexts/`.

## Goal

The run-set — `theseus-runquery` (✓), **`tpcds-query`** (new), `golem-erp`, `themis-routing`, `pythia-rca` — runs green on bp-dsk via `infra-up --kube dsk`; nightly (bp-olymp01) stays green too.

## Pre-flight

- [ ] WS-R1 DONE (`--kube dsk` + reconcile-boundary verified).
- [ ] WS-T2 DONE (TPC-DS model + `pg-tpcds` + Proteus unparse) for the `tpcds-query` context.
- [ ] Query-path services have charts (D2) + are deployable; `test-pg` reachable (T1).
- [ ] Branch `feat/c2-integration-contexts` in **both** repos.

## Tasks

- [ ] **T1 [K] — `TpcdsQueryIntegrationSpec` (tests first).** `@RequiresContext("tpcds-query")`: drive each of the four curated queries through theseus-mcp `query` → assert against the **deterministic SF1 oracle** (known row counts / a fixed aggregate value, e.g. total `store_sales` revenue for a given year). Write the assertions before the context exists (it gates via `@RequiresContext`). Add the name to `ContextNameRegistrySpec`.
- [ ] **T2 [O] — `tpcds-query` context.** `test-contexts/tpcds-query/{context.yaml, *.values.yaml}` per contracts §6.3: services `theseus`/`theseus-mcp`/`proteus`/`argos`/`kyklop`/`arges`/`ariadne`; platform `test-pg` (+ the `tpcds-load` Job, or assume a standing pre-loaded `test-pg`); `arges.values.yaml extraEnv` → `pg-tpcds` → `test-pg`; `ariadne.values.yaml` → the TPC-DS model seed; readiness incl. `Job tpcds-load Complete` (if loading per-run) + the chain Deployments.
- [ ] **T3 [K/O] — Run `tpcds-query` locally then on bp-dsk.** Local: `infra-up tpcds-query <id> <k3d>` → `:integrationTest -Pcontext=tpcds-query` → green. Then bp-dsk: `just it-bp-dsk tpcds-query` → green. This is the Goals-2+4 convergence demo.
- [ ] **T4 [K/O] — Finish `golem-erp`.** Land the pending per-service values (golem→prometheus→wiremock; ariadne seed-aligned model) + the golem-erp Shem + flip `GolemErpIntegrationSpec.liveContext = true` (testing S3.1 carry-over). Run locally + bp-dsk.
- [ ] **T5 [K/O] — `themis-routing` + `pythia-rca`.** Bring `themis-routing` green (real Kadmos/Echo); stand `pythia-rca` (minimal investigation DAG vs real Charon+Metis) — gated on those services being deployable (D2/D3). Specs first, then contexts, then local→bp-dsk.
- [ ] **T6 [K] — Re-enable `theseus-runquery` result/RLS asserts.** Flip `RunQueryIntegrationSpec.modelAlignedContext = true` once a seed-aligned model exists (testing S3.1 T7); run on bp-dsk.
- [ ] **T7 [K/O] — Assemble the run-set + bp-dsk full-run.** Define the bp-dsk on-demand run-set (all five contexts) + keep `nightly.txt` (bp-olymp01) authoritative for the scheduled nightly. Prove the **full set green on bp-dsk** via the `just it-bp-dsk` loop (sequential or namespace-isolated parallel). Document the runbook.

## DONE

- [ ] `tpcds-query` green locally **and** on bp-dsk (the four queries return correct SF1 results).
- [ ] `golem-erp` / `themis-routing` / `pythia-rca` green; `theseus-runquery` result/RLS asserts re-enabled.
- [ ] Full run-set green on bp-dsk via `infra-up --kube dsk`; nightly on bp-olymp01 unaffected. **→ MP-4.**

## Follow-ups → next stage

- After MP-4: cut the deferred release tags (contracts §9) — the program finish line.
- Graduate the `iris-session` / `sysifos-workbench` deploy-smokes (testing S3.3/S3.4) to `@RequiresContext` nightly specs (optional, post-program).
