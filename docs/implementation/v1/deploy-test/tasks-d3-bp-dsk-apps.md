# WS-D Stage 3 — bp-dsk ArgoCD apps + platform deps (waves 1–7)

> **Workstream D (Deployment), Stage 3.** Wire every module into bp-dsk via ArgoCD, wave-ordered, with its platform deps provisioned. Brings the constellation from 4 apps to the full estate (**MP-1** at the query-path wave). Mostly **[O]** olymp work driven by the kantheon deploy descriptors.
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §2 (app) + §3 (platform deps), [`master-plan.md`](./master-plan.md) WS-D waves, [`tasks-d2-charts-images.md`](./tasks-d2-charts-images.md) (descriptor index).
> **Reference.** olymp `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` (the app pattern), `platform/data/postgres/base/{databases.yaml,cluster.yaml}`, `platform/auth/keycloak/overlays/bp-dsk`, `clusters/bp-dsk/sys-image-updater/externalsecret-ghcr-pull.yaml`. Repos: **[O]** olymp (apps+platform) · **[K]** kantheon (descriptors + chartRevision flips).
> **Currently deployed (skip):** `capabilities-mcp`, `golem`, `iris`, `iris-bff`.

## Goal

Every v1 module reconciles Synced/Healthy on bp-dsk in dependency-wave order; platform deps (PG dbs, Seaweed buckets, Keycloak clients/SAs, ghcr-pull) exist; landing is reachable (backstage/kallimachos-browse best-effort).

> **Approach (decided 2026-07-05):** **single shared `kantheon` namespace** for the whole
> constellation (bare service names resolve — the local-infra topology; app values = image +
> ghcr-pull only), and **query-path-first** sequencing (foundation + waves 1–2 → MP-1, then the
> rest). Live bring-up is a hand-off (images + olymp→master merge) — see [`d3-bring-up.md`](./d3-bring-up.md).

## Pre-flight

- [x] D2 DONE (all charts render + descriptor index; **images = T6 runbook, published at bring-up**).
- [x] bp-dsk ArgoCD healthy; platform base live (✓). **Found + fixed:** the appset/projects pointed at the stale **`BoraPerusic/kantheon`** repo (→ `Collite/kantheon`); this un-breaks the `Unknown` apps.
- [x] **WS-R1 reconcile-boundary** respected (single `kantheon` app ns; test runs stay in `it-*`/`kantheon-<ctx>-<run>`).
- [x] Branch `feat/d3-bp-dsk-apps` in **both** repos (olymp + kantheon on `feat/d2-charts-images`).

## Tasks (per wave: add platform deps [O] → add apps [O] → sync + smoke → flip chartRevision→main [K/O])

- [x] **T1 [O] — Platform-dep top-up.** CNPG DB + role + `pg-{midas,hebe,kleio}-cred` ExternalSecret added (`base/{cluster,databases}.yaml` + `overlays/bp-dsk` + kustomization); **backstage uses its own existing `backstage-postgres`** (not the shared cluster). Seaweed buckets `charon` + `docwh-stage` added. `ghcr-pull` + `pg-iris`/`pg-golem` cred ClusterExternalSecrets **retargeted to the `kantheon` ns**. Keycloak clients = **hand-off** (realm JSON has none for iris/FEs yet; later waves).
- [x] **T2/T3 [O] — Waves 1–2 apps: registry/core + query path (= MP-1).** Authored `clusters/bp-dsk/apps/*` for the 19 wave-1/2 modules (ariadne/prometheus/echo/kadmos + their `-mcp`; charon/proteus/argos/kyklop/theseus/theseus-mcp/brontes/steropes/charon-mcp/metis/metis-mcp/**arges**). Single `kantheon` ns → values = image (`:testing`, `pullPolicy: Always`) + `ghcr-pull`. The 4 existing apps repointed to `feat/d2-charts-images` + migrate into `kantheon`. All 23 render green with their values overlay. **arges** `pg-midas`/`pg-tpcds` wiring deferred to Midas/WS-T (fails lazily). **Live sync = hand-off** (images + olymp→master merge — [`d3-bring-up.md`](./d3-bring-up.md)).
- [ ] **T4–T6 [O] — Waves 4–7: agents / domain / librarian / infra.** **Expanded into a per-wave task list: [`tasks-d3-waves3-7.md`](./tasks-d3-waves3-7.md) (started 2026-07-09).** themis, pythia, midas-core, midas-excel-loader, report-renderer, sysifos-bff, kleio, kallimachos, pinakes, kallimachos-mcp, hebe, whois, health, backstage + FEs (sysifos/landing/kallimachos-browse). Adds the DB-cred ClusterExternalSecrets (+ Azure KV `pg-{midas,hebe,kleio}` keys) and Keycloak realm clients. **Ground state: all kantheon charts present; no olymp apps for these modules yet.**
- [ ] **T7 [K/O] — Estate smoke + chartRevision→`master` flips.** Post-merge, on each kantheon PR merge.

## DONE (this chunk — query-path-first)

- [x] Repo-URL fix (Collite/kantheon) + single-`kantheon`-ns appset + platform-dep top-up.
- [x] Waves 1–2 apps authored + render-validated; existing 4 repointed. **Live sync pending** the T6 image publish + the olymp→master merge (runbook `d3-bring-up.md`).
- [ ] MP-1 confirmed live on bp-dsk (after the merge + images).
- [ ] Waves 3–7 + smokes + chartRevision flips (next chunk).

## Follow-ups → next stage

- The TPC-DS load (WS-T1) targets this live `test-pg`; `tpcds-query` (WS-C2) runs against this estate.
- This stage also subsumes the testing-arc **S3.3 (Iris)** + **S3.4 (Sysifos)** deploy-smokes (same GitOps-sync + smoke pattern, now part of the full estate bring-up).
