# WS-D Stage 3 — bp-dsk ArgoCD apps + platform deps (waves 1–7)

> **Workstream D (Deployment), Stage 3.** Wire every module into bp-dsk via ArgoCD, wave-ordered, with its platform deps provisioned. Brings the constellation from 4 apps to the full estate (**MP-1** at the query-path wave). Mostly **[O]** olymp work driven by the kantheon deploy descriptors.
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §2 (app) + §3 (platform deps), [`master-plan.md`](./master-plan.md) WS-D waves, [`tasks-d2-charts-images.md`](./tasks-d2-charts-images.md) (descriptor index).
> **Reference.** olymp `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` (the app pattern), `platform/data/postgres/base/{databases.yaml,cluster.yaml}`, `platform/auth/keycloak/overlays/bp-dsk`, `clusters/bp-dsk/sys-image-updater/externalsecret-ghcr-pull.yaml`. Repos: **[O]** olymp (apps+platform) · **[K]** kantheon (descriptors + chartRevision flips).
> **Currently deployed (skip):** `capabilities-mcp`, `golem`, `iris`, `iris-bff`.

## Goal

Every v1 module reconciles Synced/Healthy on bp-dsk in dependency-wave order; platform deps (PG dbs, Seaweed buckets, Keycloak clients/SAs, ghcr-pull) exist; landing is reachable (backstage/kallimachos-browse best-effort).

## Pre-flight

- [ ] D2 DONE (all charts render + images published + descriptor index).
- [ ] bp-dsk ArgoCD healthy; platform base (CNPG `postgres`, MSSQL, Redis, Seaweed, Keycloak realm `kantheon`, ESO, gateway, monitoring) live (✓).
- [ ] **WS-R1 reconcile-boundary** noted (apps live under `clusters/bp-dsk/apps/*`; test runs under `it-*` stay outside — don't cross them).
- [ ] Branch `feat/d3-bp-dsk-apps` in **both** repos.

## Tasks (per wave: add platform deps [O] → add apps [O] → sync + smoke → flip chartRevision→main [K/O])

- [ ] **T1 [O] — Platform-dep top-up.** Add CNPG databases + roles + `ExternalSecret` creds for the agents that need them (`midas`, `hebe`, `kleio`; `pythia`/`golem`/`iris`/`whois` exist) to `platform/data/postgres/base/{databases.yaml,cluster.yaml}` + `overlays/bp-dsk`. Add Seaweed buckets (charon/kleio artifacts). Add Keycloak clients/SAs (per descriptor: FE SPA clients + BFF/service accounts). Ensure `ghcr-pull` ClusterExternalSecret selector covers the new namespaces.
- [ ] **T2 [O] — Wave 1–2 apps: registry + core services.** Add `clusters/bp-dsk/apps/{ariadne,prometheus,echo,kadmos,ariadne-mcp,echo-mcp,kadmos-mcp}/{config.json,values.yaml}` (capabilities-mcp ✓). config.json `chartRevision` on the integration branch initially; values per descriptor + in-cluster wiring. Sync → Healthy.
- [ ] **T3 [O] — Wave 3 apps: query path (= MP-1).** Add `{proteus,argos,kyklop,theseus,theseus-mcp,brontes,arges,steropes}`. Wire Arges `extraEnv` for `pg-midas` + `pg-tpcds` (→ `test-pg`, from WS-T). Sync → Healthy. **This wave crossing = MP-1** (query path live → unblocks the TPC-DS load target + `tpcds-query`).
- [ ] **T4 [O] — Wave 4 apps: agents.** Add `{themis,pythia}` + `{charon-mcp,metis-mcp}` (golem/iris/iris-bff ✓). Wire DBs (pythia) + downstream. Sync → Healthy; smoke each `/health`.
- [ ] **T5 [O] — Wave 5 apps: domain.** Add `{midas-core,midas-loaders-excel,report-renderer,golem-investment,sysifos-bff}` + `frontends/sysifos`. (Golem-Investment = the assembled Shem from Midas S3.1; sysifos pairs with testing S3.4.) Wire `midas` DB + Keycloak SAs. Sync → Healthy + the testing-S3.3/S3.4 deploy-smokes.
- [ ] **T6 [O] — Wave 6–7 apps: librarian + infra.** Add `{kleio,kallimachos,pinakes,kallimachos-mcp}` + `infra/{whois,health,backstage}` + `frontends/{landing,kallimachos-browse}`. **landing must work** (smoke the host); **backstage + kallimachos-browse best-effort** (don't gate). Sync.
- [ ] **T7 [K/O] — Estate smoke + chartRevision flips.** Confirm all apps Synced/Healthy (`just apps dsk`); run the per-stack smokes (Iris session, Sysifos workbench, a TPC-DS query via the live path). Flip each app's `config.json chartRevision` → `main` as its kantheon PR merges (contracts §2.1). Record the live estate in the master-plan status.

## DONE

- [ ] Full estate reconciled Synced/Healthy on bp-dsk (landing in; backstage/kallimachos-browse best-effort).
- [ ] Query path live (MP-1) — Arges reachable with `pg-midas` + `pg-tpcds`.
- [ ] Per-stack smokes green; chartRevisions flipped to `main` on merge.

## Follow-ups → next stage

- The TPC-DS load (WS-T1) targets this live `test-pg`; `tpcds-query` (WS-C2) runs against this estate.
- This stage also subsumes the testing-arc **S3.3 (Iris)** + **S3.4 (Sysifos)** deploy-smokes (same GitOps-sync + smoke pattern, now part of the full estate bring-up).
