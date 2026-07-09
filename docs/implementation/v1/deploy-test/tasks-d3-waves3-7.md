# WS-D3 — bp-dsk waves 4–7 (agents / domain / librarian / infra)

> **Workstream D (Deployment), Stage 3, chunk 2.** Stand up the **rest of the estate** on bp-dsk
> after the query-path chunk (waves 1–2 = MP-1). This is the last open program-DoD item
> ("full constellation reconciled on bp-dsk"). Expands the deferred `T4–T6` bullet of
> [`tasks-d3-bp-dsk-apps.md`](./tasks-d3-bp-dsk-apps.md) into per-wave tasks.
>
> **Reads with.** [`tasks-d3-bp-dsk-apps.md`](./tasks-d3-bp-dsk-apps.md) (waves 1–2, the app pattern),
> [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §2 (app) + §3 (platform deps),
> [`../../../architecture/deploy-test/deploy-descriptors.md`](../../../architecture/deploy-test/deploy-descriptors.md) (the descriptor index),
> [`d3-bring-up.md`](./d3-bring-up.md) (the live-sync hand-off recipe).
>
> *Created 2026-07-09. Owner: Bora. Repos: **[O]** olymp apps+platform · **[K]** kantheon descriptors/chartRevision.*

## Ground state (verified 2026-07-09)

- **Kantheon charts: all present.** Every wave 4–7 module has a `k8s/` chart (D2): themis, pythia,
  `midas/core`, `midas/loaders/excel`, report-renderer, sysifos-bff, `frontends/sysifos`, hebe
  (`modules/cli-app`), kleio, kallimachos, pinakes, `tools/kallimachos-mcp`, whois, health,
  `frontends/landing`, backstage, `frontends/kallimachos-browse`. **No kantheon chart work required.**
- **Olymp bp-dsk apps: waves 1–2 only** (24 apps — query-path + registry/core + original 4).
  **None of the wave 4–7 modules have an olymp app yet.** Olymp on `master`, clean.
- **Approach unchanged from waves 1–2:** single shared `kantheon` namespace; app `values.yaml` =
  image (`:testing`, `pullPolicy: Always`) + `ghcr-pull`; live sync is a hand-off (images +
  olymp→master merge → ArgoCD). Per D3 §7-D3: **landing is in scope (must work)**;
  **backstage + kallimachos-browse are best-effort** (do not gate the program).

## Per-wave task shape (same as waves 1–2)

For each wave: **platform deps [O]** → **app dirs [O]** (`clusters/bp-dsk/apps/<m>/{config.json,values.yaml}` per the iris pattern) → **publish any missing `:testing` image** (hand-off, Bora's `write:packages` PAT) → **sync + smoke** (hand-off, cluster) → **chartRevision→`master` flip [K/O]** on merge.

## Pre-flight

- [ ] Branch `feat/d3-waves3-7` in **both** repos (olymp apps + kantheon chartRevision flips/descriptors).
- [x] D2 charts render (all wave 4–7 charts present; CI `helm-charts` golden-diff green).
- [ ] Confirm which wave 4–7 `:testing` images already exist in GHCR vs. need a first publish
      (`d2-image-publish.md` per-module commands; Python amd64 + `midas-excel-loader` basename + backstage custom build caveats).

---

## Wave 4 — agents (`themis`, `pythia`)

Both already have integration contexts proving they boot in-cluster (`themis-routing`, `pythia-rca`),
so their wiring is known-good; this is the standing (prod) app, not a per-run context.

- [ ] **T1 [O] — Platform deps.** `pythia` DB: add the `pythia` CNPG database + role +
      `pg-pythia-cred` ClusterExternalSecret into the `kantheon` ns (mirror the pg-golem/pg-iris
      pattern; T1 of waves 1–2 added midas/hebe/kleio but **not** pythia). `themis`: none (stateless;
      reads capabilities-mcp + kadmos/echo/prometheus, all already deployed).
- [ ] **T2 [O] — App dirs.** `apps/themis/` (themis-mcp + the themis agent per its chart) and
      `apps/pythia/`. Values = image `:testing` + ghcr-pull; pythia `extraEnv` → `PYTHIA_DB_*` from
      `pg-pythia-cred` (prod wants persistence on, unlike the DB-less `pythia-rca` context).
- [ ] **T3 — Images.** Publish `themis-mcp:testing` + `pythia:testing` if not already in GHCR
      (both were published for the C2 contexts — likely present; verify).
- [ ] **T4 — Sync + smoke (hand-off).** ArgoCD Synced/Healthy; smoke: themis MCP `resolve` reachable,
      pythia `POST /v1/investigations` 202 + admission 403 (mirrors the context assertions).
- [ ] **T5 [K/O] — chartRevision→`master`** for themis + pythia on merge.

## Wave 5 — domain (`midas-core`, `midas-excel-loader`, `report-renderer`, `sysifos-bff`, `sysifos` FE)

- [ ] **T1 [O] — Platform deps.** `midas` DB already added (waves-1–2 T1: `pg-midas-cred`) — verify it
      lands in `kantheon` ns. `midas-excel-loader`: blob scratch `emptyDir` (no DB). `report-renderer`:
      none. `sysifos-bff`: its datastore (confirm — reuses midas PG or own?). `sysifos` FE + `sysifos-bff`:
      Keycloak client (realm JSON — hand-off, like iris).
- [ ] **T2 [O] — App dirs.** 5 apps. `midas/loaders/excel` app image basename is `midas-excel-loader`
      (descriptor note). `sysifos` FE = nginx chart (image + ghcr-pull).
- [ ] **T3 — Images.** Publish `midas:testing`, `midas-excel-loader:testing`, `report-renderer:testing`,
      `sysifos-bff:testing`, `sysifos:testing` (FE nginx, amd64). Several were part of the v0.6.0 sweep —
      verify GHCR.
- [ ] **T4 — Sync + smoke (hand-off).** Midas answer path smoke; report-renderer health; sysifos FE loads.
- [ ] **T5 [K/O] — chartRevision→`master`** for the 5 modules on merge.

## Wave 6 — librarian (`hebe`, `kleio`, `kallimachos`, `pinakes`, `kallimachos-mcp`)

- [ ] **T1 [O] — Platform deps.** `hebe` DB (`pg-hebe-cred`, added waves-1–2 T1 — schema per instance;
      k8s profile → in-cluster PG); `kleio` DB (`pg-kleio-cred`, added; **pgvector/AGE** extensions —
      confirm the CNPG image/extensions support them, else a kleio-specific PG). `kallimachos`/`pinakes`/
      `kallimachos-mcp`: confirm deps (pinakes = artifact store? kallimachos = browse/index).
      Hebe Keycloak OBO client for any platform-reaching profile.
- [ ] **T2 [O] — App dirs.** 5 apps. Hebe app = the `cli-app` server-mode image (`:testing`, port 8765),
      delivered via the dynamic per-instance ApplicationSet if multi-instance (mirror `bp-dsk-golems`),
      else a single standing app.
- [ ] **T3 — Images.** Publish `hebe:testing` (from `:agents:hebe:modules:cli-app:jib` — the path-map
      fix landed 2026-07-09), `kleio`, `kallimachos`, `pinakes`, `kallimachos-mcp`.
- [ ] **T4 — Sync + smoke (hand-off).** Hebe web console reachable; kleio vector/graph plane up;
      kallimachos index reachable.
- [ ] **T5 [K/O] — chartRevision→`master`** on merge.

## Wave 7 — infra (`whois`, `health`, `landing` FE — in scope; `backstage`, `kallimachos-browse` — best-effort)

- [ ] **T1 [O] — Platform deps.** `whois`: its **own Postgres** (not the shared cluster — per the fork,
      whois carries its own PG) + OPA bundle; `health`: none (aggregator — check targets re-pointed to the
      kantheon estate); `landing`: none (nginx); `backstage`: its existing `backstage-postgres` (waves-1–2
      T1 noted it uses its own). Keycloak clients for the FEs (hand-off).
- [ ] **T2 [O] — App dirs.** `whois`, `health`, `landing` (in scope). `backstage`, `kallimachos-browse`
      (best-effort — author if cheap, do not block the program).
- [ ] **T3 — Images.** Publish `whois:testing`, `health:testing`, `landing:testing` (FE nginx); backstage
      custom build (best-effort).
- [ ] **T4 — Sync + smoke (hand-off).** **`landing` reachable = the program gate** (D3 §7-D3);
      whois `/health` + role lookup; health roll-up green.
- [ ] **T5 [K/O] — chartRevision→`master`** on merge.

---

## DONE (chunk 2 → program DONE)

- [ ] Waves 4–7 apps authored [O] + platform deps provisioned; all render + `infra-up --dry-run` clean.
- [ ] Every wave 4–7 module reconciles **Synced/Healthy** on bp-dsk (landing in; backstage/
      kallimachos-browse best-effort).
- [ ] Estate smokes green; **`landing` reachable**.
- [ ] chartRevision→`master` flips landed for all waves (T7 of the parent list).
- [ ] tasks-overview §4 program-DoD "full constellation reconciled on bp-dsk" checked → **program DONE**.

## Hand-off boundaries (not autonomous)

- **Image publishing** needs Bora's `write:packages` PAT (or run the release-image workflow / `just publish-image`).
- **olymp→master push** + **ArgoCD sync** are cluster/GitOps operations (auto-mode-gated) — the developer's
  hand, per `d3-bring-up.md`. Authoring the olymp app dirs + platform-dep YAML on a branch is not.
- **Keycloak realm clients** (iris/sysifos/landing/hebe) are realm-JSON hand-offs (as in waves 1–2 T1).
