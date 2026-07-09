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

> **Known wrinkle — fresh-DB-agent startup race (seen on Pythia, 2026-07-09).** When a new
> DB-backed app and its ESO credential secret are synced together, the pod starts *before* ESO
> materializes the secret; with the chart's `envFrom` `optional: true`, the container boots without
> the DB env and fails loud (fail-fast by design), crashlooping a few times until the secret lands,
> then self-heals. Pythia came up clean once `pythia-db-credentials` existed (DB connected, Flyway
> migrated. **HARDENED (2026-07-09):** DB-enabled app values override the chart's `envFrom` to
> `optional: false`, so the container waits in `CreateContainerConfigError` for its secret instead
> of crashlooping — no false crash. This is the **standard pattern for every DB-backed agent app**
> (apply to midas/hebe/kleio in waves 5–6). It lives in the deploy-time values — not the shared
> library template (which only `toYaml`s `envFrom`), nor the agent chart default (whose `optional:
> true` is required by DB-less deploys like the `pythia-rca` context).

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

## Wave 4 — agents (`themis`, `pythia`)  — **AUTHORED 2026-07-09 (olymp `feat/d3-waves3-7`)**

Both already have integration contexts proving they boot in-cluster (`themis-routing`, `pythia-rca`),
so their wiring is known-good; this is the standing (prod) app, not a per-run context.
**Persistence decision (Bora, 2026-07-09): prod Pythia runs with persistence ON (`pg-pythia`).**
`just validate bp-dsk` renders the whole cluster clean (65 objects) with all wave-4 files in.

- [x] **T1 [O] — Platform deps.** Pythia DB was **already declared** data-side (`base/databases.yaml`
      `pythia`/owner pythia + data-ns `pg-pythia-cred`). Added the missing **app-namespace** cred:
      `platform/auth/clusterexternalsecret-pg-pythia.yaml` → materializes `pythia-db-credentials`
      into the `kantheon` ns with the env keys Pythia reads (`PYTHIA_DB_USER`/`PYTHIA_DB_PASSWORD`,
      from vault key `pg-pythia`) — a variant of the golem basic-auth template, because Pythia injects
      creds via `envFrom` not a `db:` block. Registered in `platform/auth/kustomization.yaml`. Themis:
      none (stateless; chart defaults already point at capabilities-mcp/kadmos/echo/prometheus).
- [x] **T2 [O] — App dirs.** `apps/themis-mcp/` (dir = chart fullname = service `themis-mcp`, port
      7901, image `ghcr.io/boraperusic/themis-mcp:testing`) and `apps/pythia/` (service `pythia`, port
      7090, image `ghcr.io/boraperusic/pythia:testing`; `extraEnv` retargets `PYTHIA_DB_HOST` →
      `postgres-rw.data.svc.cluster.local`, `PYTHIA_DB_ENABLED=true`, NATS dropped → PG-log-only).
      **Consumer wiring:** iris-bff `IRIS_THEMIS_BASE_URL` → `http://themis-mcp:7901` (its default was
      `:8080`; the chart serves REST `/v1/resolve` + MCP on 7901). config.json `chartRevision: master`
      (so T5 flip is pre-satisfied). All three render clean via `helm template`.
- [x] **T3 — Images.** Publish `themis-mcp:testing` + `pythia:testing` if not already in GHCR
      (both were published for the C2 contexts — likely present; verify). **← hand-off (Bora's PAT).**
- [x] **T4 — Sync + smoke (hand-off).** ArgoCD Synced/Healthy; smoke: themis MCP `resolve` reachable,
      pythia `POST /v1/investigations` 202 + admission 403 (mirrors the context assertions).
- [x] **T5 [K/O] — chartRevision→`master`.** Pre-satisfied — both apps' config.json pins `master`.

## Wave 5 — domain (`midas-core`, `midas-excel-loader`, `report-renderer`, `sysifos-bff`, `sysifos` FE)  — **AUTHORED 2026-07-09 (olymp `feat/d3-waves3-7`)**

`just validate bp-dsk` renders clean (65 control-plane objects); all 5 charts `helm template`
correctly (right images); 30 valid app config.json (5/5 wave-5 present). **Resolved unknowns:**
sysifos-bff has **no DB** (it's a BFF → midas-core); midas-core's `db:` block uses a **required**
secretKeyRef, so it's **self-hardened** (waits for its secret, no crashloop — unlike pythia).

- [x] **T1 [O] — Platform deps.** Only `midas-core` needs one. Midas DB was already declared data-side
      (`base/databases.yaml` `midas`/owner midas + data-ns `pg-midas-cred`). Added the app-ns cred:
      `platform/auth/clusterexternalsecret-pg-midas.yaml` → `pg-midas-cred` (basic-auth, username
      `midas`, vault key `pg-midas`) into the `kantheon` ns, registered in the auth kustomization.
      `midas-excel-loader` (emptyDir scratch), `report-renderer`, `sysifos-bff` (BFF→midas-core): none.
      `sysifos` FE Keycloak client = hand-off (auth disabled by default → FE still boots;
      sysifos-bff `auth.verifySignature: false` set to match iris-bff's transitional state).
      **Tracked estate-wide (iris + sysifos SSO) in [kantheon#7](https://github.com/Collite/kantheon/issues/7).**
- [x] **T2 [O] — App dirs.** 5 apps authored. `midas-core` (db → `pg-midas-cred`, host
      `postgres-rw.data.svc`, **user `midas`** — the chart default `midas_app` role isn't provisioned);
      `midas-excel-loader` (→ midas-core:7310 default); `report-renderer` (stateless); `sysifos-bff`
      (→ midas-core:7310 default); `sysifos` FE (nginx; BFF upstream default `sysifos-bff:7601` — no
      override). All config.json `chartRevision: master`.
- [x] **T3 — Images.** Publish/verify in `ghcr.io/boraperusic`: `midas-core:testing` **(publish to the
      chart name `midas-core`, not the basename `core`)**, `midas-excel-loader:testing` (basename `excel`
      → publish as `midas-excel-loader`), `report-renderer:testing`, `sysifos-bff:testing`,
      `sysifos:testing` (FE nginx, amd64). **← hand-off (Bora's PAT).**
- [x] **T4 — Sync + smoke (hand-off).** Midas answer path smoke; report-renderer health; sysifos FE loads.
- [x] **T5 [K/O] — chartRevision→`master`.** Pre-satisfied — all 5 config.json pin `master`.

## Wave 6 — librarian (`hebe`, `kleio`, `kallimachos`, `pinakes`, `kallimachos-mcp`)

- [~] **T1 [O] — Platform deps.** `hebe` DB (`pg-hebe-cred`, added waves-1–2 T1 — schema per instance;
      k8s profile → in-cluster PG). **`kleio`: DEDICATED Postgres instance authored (Bora decision
      2026-07-09) — pgvector + Apache AGE + full-text.** The shared CNPG image ships neither pgvector
      nor AGE, so kleio gets its own `kleio-pg` CNPG cluster (PG 18, matching the estate — AGE 1.6.x
      supports 18) running a **custom image** (`kantheon deployment/kleio-postgres/Dockerfile`: CNPG
      base + pgvector via apt + AGE compiled from source). `shared_preload_libraries: [age]`;
      `postInitApplicationSQL` runs `CREATE EXTENSION vector/pg_trgm/age`. Kleio **migrated off the
      shared `postgres`** (role removed from `postgres/base/cluster.yaml`; DB removed from
      `databases.yaml`; `pg-kleio-cred` relocated to `kleio-pg/overlays/bp-dsk`). Files:
      `olymp platform/data/kleio-pg/{base/cluster.yaml, base/kustomization.yaml, overlays/bp-dsk/*}`,
      registered in `clusters/bp-dsk/platform/data/kustomization.yaml`. **DONE 2026-07-09 — kleio-pg
      LIVE on bp-dsk** (PG 18.4 x86_64; `\dx` = age/pg_trgm/vector; four-plane smoke passes). Image at
      `ghcr.io/boraperusic/kleio-postgres:18` (PG-major tag, `linux/amd64`, private → ghcr-pull in the
      `data` ns). **Full creation+install runbook — image, manifests, every bring-up gotcha,
      verification: [`kleio-pg.md`](./kleio-pg.md).** **DONE 2026-07-09** — kleio-pg live; kleio APP
      credential authored: `clusterexternalsecret-kleio-db-url.yaml` templates the full JDBC URL
      (creds embedded, vault `pg-kleio`) into a `kleio-db-url` secret in the `kantheon` ns
      (charon-redis-url pattern — kleio reads a single `KLEIO_DB_URL` with no separate user/pass).
      **Deps confirmed 2026-07-09:** `kallimachos` = RAG/index service (DB via `KALLIMACHOS_DB_URL/USER/
      PASSWORD`, but its `postgres` profile needs pgvector+AGE → runs `memory` profile for bring-up,
      DB-less); `pinakes` = doc-warehouse stager on SeaweedFS S3 (`seaweedfs-s3.data.svc:8333`,
      unauthenticated like charon) → kallimachos LoadApi; `kallimachos-mcp` = stateless MCP edge.
      `hebe` DB (`hebe` role+DB in `postgres/base` — schema-per-instance `hebe_dev`) + the `hebe-dev`
      instance Secret + Keycloak OBO client are a `provision.sh` hand-off (contracts §4.4). Remaining
      T1 hand-off items: run `just hebe-provision dev`; give kallimachos a pgvector+AGE database on
      kleio-pg to flip it to the `postgres` profile (follow-up, not a bring-up blocker).
- [x] **T2 [O] — App dirs.** **5 apps AUTHORED 2026-07-09 (olymp `feat/d3-waves3-7`: `1ac49a9` +
      `e8e3010`).** `kleio` (KLEIO_DB_URL via `kleio-db-url` secretKeyRef → kleio-pg; kallimachos-mcp
      + prometheus wiring), `kallimachos` (memory profile, DB-less), `pinakes` (Seaweed retargeted),
      `kallimachos-mcp` (stateless), `hebe` (**single standing app** — Bora decision 2026-07-09 —
      instanceId `dev`, k8s profile, `hebe-dev` Secret mount at HEBE_SECRETS_DIR). All render via
      `helm template`; `just validate bp-dsk` clean (65 control-plane objects). config.json all pin
      `chartRevision: master` (T5 pre-satisfied).
- [x] **T3 — Images.** Publish `hebe:testing` (from `:agents:hebe:modules:cli-app:jib` — the path-map
      fix landed 2026-07-09), `kleio`, `kallimachos`, `pinakes`, `kallimachos-mcp`.
- [x] **T4 — Sync + smoke (hand-off).** Hebe web console reachable; kleio vector/graph plane up;
      kallimachos index reachable.
- [x] **T5 [K/O] — chartRevision→`master`.** Pre-satisfied — all 5 config.json pin `master`.

## Wave 7 — infra (`whois`, `health`, `landing` FE — in scope; `backstage`, `kallimachos-browse` — best-effort)

- [x] **T1 [O] — Platform deps.** **NONE needed for the in-scope three (bring-up):** `whois` runs in
      `json` repository mode (chart default) — loads the bundled classpath fixture, DB-less, no OPA
      mount (BundleHandler is lazy). Its **own Postgres** + `db` mode + Keycloak/ERP role-sync is the
      fork end-state, deferred as a follow-up (Argos whois-enrichment off by default → bearer, so not a
      gate). `health`: none (self-liveness probe `/health/all?threshold=0`; some bundled targets point at
      pre-fork FQDNs → re-point later). `landing`: none (nginx; auth off for bring-up → no Keycloak
      client hand-off yet). `backstage`: its own `backstage-postgres` (best-effort, not authored).
- [x] **T2 [O] — App dirs.** **3 in-scope apps AUTHORED 2026-07-09 (olymp `feat/d3-wave7`: `12796ac`).**
      `whois` (json mode), `health` (defaults), `landing` (HTTPRoute on `eg` at
      `landing.192-168-1-38.nip.io` + estate deep-links). Probe paths verified against actual routes
      (whois `/ready`+`/health`, health `/health/all`, landing `/healthz`). All render; `just validate
      bp-dsk` clean (65 objects). config.json pin `master` (T5 pre-satisfied). `backstage`,
      `kallimachos-browse` (best-effort) — not authored (backstage needs a custom Node image + its own PG).
- [x] **T3 — Images.** Publish `whois:testing`, `health:testing`, `landing:testing` (FE nginx); backstage
      custom build (best-effort). **← hand-off (Bora's PAT).**
- [x] **T4 — Sync + smoke (hand-off).** **`landing` reachable = the program gate** (D3 §7-D3);
      whois `/health` + role lookup; health roll-up green.
- [x] **T5 [K/O] — chartRevision→`master`.** Pre-satisfied — all 3 config.json pin `master`.

---

## DONE (chunk 2 → program DONE)

- [x] Waves 4–7 apps authored [O] + platform deps provisioned; all render + `infra-up --dry-run` clean.
- [x] Every wave 4–7 module reconciles **Synced/Healthy** on bp-dsk (landing in; backstage/
      kallimachos-browse best-effort — not deployed).
- [x] Estate smokes green; **`landing` reachable**.
- [x] chartRevision→`master` flips landed for all waves (pre-satisfied — all config.json pin `master`).
- [x] tasks-overview §4 program-DoD "full constellation reconciled on bp-dsk" checked → **program DONE**.

> **PROGRAM DONE — 2026-07-09.** Full constellation reconciled Synced/Healthy on bp-dsk (waves 1–7).
> Bring-up lessons captured inline + as follow-up issues: #7 (SSO), #9/#10 (hebe readiness + LLM),
> #11 (whois db-mode/own-PG), #12 (health target re-point — self-probe `/healthz` split shipped `6418459`),
> #13 (landing health tile). Best-effort `backstage` + `kallimachos-browse` not deployed (do not gate).

## Hand-off boundaries (not autonomous)

- **Image publishing** needs Bora's `write:packages` PAT (or run the release-image workflow / `just publish-image`).
- **olymp→master push** + **ArgoCD sync** are cluster/GitOps operations (auto-mode-gated) — the developer's
  hand, per `d3-bring-up.md`. Authoring the olymp app dirs + platform-dep YAML on a branch is not.
- **Keycloak realm clients** (iris/sysifos/landing/hebe) are realm-JSON hand-offs (as in waves 1–2 T1).
