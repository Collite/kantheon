# bp-dsk deployment — session handoff (gotchas + deferred tasks)

> **What this is.** A running log of the **WS-D3 query-path bring-up on bp-dsk** — the decisions,
> the gotchas each service hit and how they were fixed, and everything still deferred. Read it
> before continuing the estate bring-up. Companions: [`d3-bring-up.md`](./d3-bring-up.md) (the
> deploy runbook), [`d2-image-publish.md`](./d2-image-publish.md) (image publish), and
> [`t1-tpcds-load.md`](./t1-tpcds-load.md) (TPC-DS load). Estate model + waves: `tasks-d3-bp-dsk-apps.md`.
>
> *Session 2026-07-06. Owner: Bora.*

---

## 1. Current state

**Cluster:** `kubectl --context dsk`. The constellation runs in a **single `kantheon` namespace**
(see §2). Platform data services live in the **`data`** namespace (this is the #1 gotcha — §3).

**Wave 1–2 (registry/core + query path) — essentially green.** 21/24 pods Healthy after the
fixes below. The theseus→proteus→argos→kyklop→arges query path is up (arges/brontes in *fixture*
mode until WS-T2 — §5). Remaining reds are **image**, not config:
- `charon` — needs a **rebuild** to pick up the `mapOf`→`buildJsonObject` 406 fix (§4).
- `iris` — needs `iris:testing` **published** (FE, `just publish-fe-image`).
- the 3 **Python** images (`kadmos`/`metis`/`steropes`) — publish via the GH Actions workflow (§6).

**TPC-DS (WS-T1):** `test-pg` CNPG cluster + `tpc-ds-1g` DB + creds are **live**; the SF1 data is
**staged** to the `tpcds-staging` Seaweed bucket (27 objects, byte-verified). The `tpcds-load`
Job is authored but **not yet run** (T5) — see [`t1-tpcds-load.md`](./t1-tpcds-load.md).

---

## 2. Load-bearing decisions made this session

1. **Single shared `kantheon` namespace** for the whole constellation. The appset
   `destination.namespace` is `kantheon` (was per-app `{{.path.basename}}`). Rationale: the charts'
   env-agnostic **bare** in-cluster service names resolve in one namespace (the proven local-infra
   topology), so app `values.yaml` = image + `ghcr-pull` only, no per-app FQDN wiring **between
   constellation services**. (Cross-namespace to the `data` tier still needs FQDNs — §3.)
2. **Repo rename fixed:** olymp pointed at the **stale `github.com/BoraPerusic/kantheon`**; repointed
   to **`Collite/kantheon`** (appset + projects + bootstrap + CLAUDE.md, all 3 clusters). This is
   what made the pre-existing apps show `Unknown`.
3. **`chartRevision: master`.** D1/D2 merged to kantheon master and the `feat/d2-charts-images`
   branch was deleted → every app's `config.json` was flipped to `master` (the planned T7 flip,
   triggered early by the branch delete). ArgoCD reconciles olymp **master** (HEAD), so olymp changes
   only take effect on merge-to-master.
4. **Bring-up tag = `:testing` / `pullPolicy: Always`** for all apps (mutable, auto-pulled on
   republish). Release tags (`iris/v…` etc.) are cut together at **MP-4**, not per-service now.

---

## 3. Cross-cutting gotchas (the patterns that bit us repeatedly)

These recur on **every** DB/infra-backed service — expect them on the later waves too.

- **Platform data services live in the `data` namespace, not `kantheon`.** Their real service names:
  - Postgres (shared agent DB): **`postgres-rw.data.svc.cluster.local:5432`** (CNPG `<cluster>-rw`).
  - Redis: **`redis.data.svc.cluster.local:6379`** (NOT `data-redis`).
  - Seaweed S3: **`seaweedfs-s3.data.svc.cluster.local:8333`** (NOT `data-seaweedfs`).
  - TPC-DS warehouse: **`test-pg-rw.data.svc.cluster.local:5432`**.
  Chart defaults use bare/local-dev names (`postgres`, `data-redis`, `data-seaweedfs`) that **don't
  resolve from the `kantheon` ns** → `UnknownHostException`. Override in the app `values.yaml`.
- **`extraEnv` is REPLACED wholesale, not merged** (contracts §1.3). When you override it in an app's
  values, carry the **entire** list (e.g. charon needed all three of S3/Redis/connections-path).
- **Chart defaults are local-dev values** — bare hostnames, local ports, dev DB names/users
  (`prometheus`/`postgres`), telemetry off. Every cluster deploy overrides what's cluster-specific.
- **DB-cred pattern (vault → app namespace):** a role password lives at an **Azure KV key**; a data-ns
  `ExternalSecret` materializes it for CNPG; a **`ClusterExternalSecret`** (in
  `clusters/bp-dsk/platform/auth/`, `namespaceSelectors: kantheon`) materializes it into the
  `kantheon` ns for the app; the chart reads it via `secretKeyRef`. For a value that must be
  **templated** (e.g. a Redis URL that embeds the password), the ClusterExternalSecret's
  `target.template` builds it (see `charon-redis-url`).
- **Worker readiness is gated on connections.** `arges`/`brontes` `/ready` returns **503** ("no
  connections configured") **by design** when no JDBC connection is wired — so the pod stays `0/1`
  and, transitively, **kyklop** can't find a healthy worker. `*_USE_FIXTURE=true` is the designed
  bring-up mode (Ready with a fixture connection, translator probe skipped).
- **Ktor `respond(mapOf(...))` → HTTP 406.** A raw `Map` has no serializer; with `ContentNegotiation`
  the route can't negotiate a representation → 406 → failing `/health` probe → CrashLoop. Services
  without `ContentNegotiation` "work" only via a `toString()` fallback (latent trap). **Always
  `buildJsonObject`.** Now documented: EXAMPLES.md §2a + AGENTS.md.
- **helm v4 vs v3 golden drift.** The `validate-charts` goldens were captured with local helm v4
  (blank line before each `---`); CI runs helm v3.16.2. Fixed by normalizing inter-document
  whitespace in `shared/charts/validate.sh` — keep it version-agnostic.
- **`docker manifest inspect` lies on private GHCR in a loop** (osxkeychain rate-limits → false
  MISS). Check package existence in the **GitHub UI** (`github.com/boraperusic?tab=packages`) or a
  single `docker pull`.
- **Python images can't be built on Apple Silicon** — `buildx --platform linux/amd64` runs `uv sync`
  under QEMU and segfaults (signal 11). Build them in CI (§6). Kotlin/Jib + FE-nginx build fine on the Mac.

---

## 4. Per-service fixes applied this session

| Service | Symptom | Cause | Fix | Where |
|---|---|---|---|---|
| olymp (all) | 3 apps `Unknown` | appset pointed at stale `BoraPerusic/kantheon` | repoint → `Collite/kantheon` | olymp (3 clusters) |
| all apps | `unable to resolve feat/d2-charts-images` | chart branch deleted after merge | flip `chartRevision`→`master` | olymp `apps/*/config.json` |
| **kyklop** | polls only brontes+steropes; can't dispatch to arges | `KYKLOP_WORKER_ARGES_ENDPOINT` missing from chart default | add `arges:7303` to chart `extraEnv` | kantheon `services/kyklop` |
| **ariadne/echo/kadmos-mcp** | CrashLoop `No config 'capabilities-mcp'` | forked without the `capabilities-mcp{}` conf block + no `CAPABILITIES_MCP_URL` env | set `CAPABILITIES_MCP_URL` in chart `extraEnv` (runtime) **+** add the conf block (code, needs rebuild) | kantheon `tools/*-mcp` |
| **charon-mcp** | registered to dead port | stale `CAPABILITIES_MCP_URL=…:7080` | → `capabilities-mcp:7501` | kantheon `tools/charon-mcp` |
| **metis-mcp** | CrashLoop, probes `/health/ready` → 404 | wrong probe paths (`/health/{ready,live}`) | → `/ready` + `/health` (wrapper family default) | kantheon `tools/metis-mcp` (+ kallimachos-mcp) |
| **charon** | `MountVolume … configmap "charon-connections" not found` | chart mounts a deploy-provided ConfigMap that doesn't exist | `connections.configMapName: ""` → blob-only | olymp `apps/charon` |
| **charon** | `UnknownHostException: data-redis` (fatal at boot) | bare `data-redis`/`data-seaweedfs` don't resolve | override `CHARON_REDIS_URL`/`CHARON_S3_ENDPOINT` → `*.data.svc` | olymp `apps/charon` |
| **charon** | Redis `NOAUTH` | data-ns Redis runs `--requirepass`; charon builds the client from the URL only | `charon-redis-url` ClusterExternalSecret templates `redis://:<pw>@…` from vault; app reads via `secretKeyRef` | olymp `platform/auth` + `apps/charon` |
| **charon** | `/health` → 406 → CrashLoop | `respond(mapOf(...))` (charon installs ContentNegotiation) | `buildJsonObject` (needs image rebuild) | kantheon `services/charon` |
| **prometheus** | `UnknownHostException: postgres` (Flyway on boot) | dev-default DB host/name/user, none provisioned | run the app's **H2 `test` profile** (`SPRING_PROFILES_ACTIVE=test`) for bring-up | olymp `apps/prometheus` |
| **arges / brontes** | `0/1`, `/ready` → 503 | no JDBC connection wired (by design) | `ARGES_USE_FIXTURE` / `BRONTES_USE_FIXTURE = true` | olymp `apps/{arges,brontes}` |
| **iris** | `ImagePullBackOff` | pinned to unpublished `0.1.0` | flip to `:testing`/`Always` (publish `iris:testing`) | olymp `apps/iris` |
| all 10 Ktor svcs | latent 406 | `respond(mapOf)` | swept → `buildJsonObject`; rule documented | kantheon (charon, kleio, proteus, ariadne, kallimachos, kyklop, pinakes, theseus, arges, brontes) |
| CI: ariadne test | `NoClassDefFoundError WorldDef` | tatrman `ttr-metadata:0.8.4` referenced a class absent from `ttr-parser:0.8.4` (broken upstream publish) | bump tatrman → **0.8.6** (Bora) | `gradle/libs.versions.toml` |
| CI: midas RLS | `permission denied to create extension pgcrypto` | test role lacked DB-level CREATE (prod: midas_app owns the DB) | `GRANT ALL PRIVILEGES ON DATABASE … TO midas_app` in the spec | kantheon `agents/midas/core` componentTest |
| CI: helm-charts | all 40 charts "drift" | helm v4 (local) vs v3 (CI) blank-line-before-`---` | normalize whitespace in `validate.sh` | kantheon `shared/charts` |

---

## 5. Fixture mode — arges & brontes (temporary)

Both workers are in `*_USE_FIXTURE=true` so the pods go Ready and kyklop has a healthy worker. This
**stubs the translator + serves fixture data** — the query path is structurally live but not hitting
a real DB. Drop the flag once a real connection is wired: **arges** → `pg-tpcds` (WS-T2, on the now-
live `test-pg`) or `pg-midas`; **brontes** → an MSSQL connection. See WS-T2.

---

## 6. Images

- **Kotlin/Jib** (`just publish-image <path> testing`) — build fine on the Mac (multi-arch). Wave-1/2
  set: ariadne, prometheus, echo, ariadne-mcp, echo-mcp, kadmos-mcp, charon-mcp, metis-mcp,
  theseus-mcp, charon, proteus, argos, kyklop, theseus, brontes, arges. **Transient GHCR `500` on
  manifest PUT is common — just retry** (don't `break` the loop).
- **FE nginx** (`just publish-fe-image iris testing`) — amd64, no QEMU. **Prereq:** generate the
  gitignored bindings first: `(cd shared/libs/ts/envelope-ts && npm ci && npm run gen)`.
- **Python** (`kadmos`/`metis`/`steropes`) — **use the `publish-python-images` GH Actions workflow**
  (native amd64; runs `just proto-py` first). One-time: add a repo secret **`GHCR_TOKEN`**
  (write:packages PAT — the workflow's built-in token can't push to the `boraperusic` user namespace).
  See [`d2-image-publish.md`](./d2-image-publish.md).
- **Check existence** in the GitHub UI, not a CLI loop (§3).

---

## 7. Deferred tasks

**Images to publish (to finish wave 1–2):**
- [ ] Rebuild **charon** (`:testing`) — the `mapOf` 406 fix is in source only.
- [ ] Publish **iris** (`just publish-fe-image iris testing`) — after `envelope-ts` gen.
- [ ] Publish the 3 **Python** images via the `publish-python-images` workflow (needs `GHCR_TOKEN` secret).
- [ ] (optional) rebuild the 3 mcp wrappers so they carry the `capabilities-mcp{}` conf block (they run
      today via the chart env; the code fix is defense-in-depth).

**WS-T (TPC-DS):**
- [ ] **T5** — run `tpcds-load` (apply the Job, verify SF1 counts) — [`t1-tpcds-load.md`](./t1-tpcds-load.md). Data is staged.
- [ ] **WS-T2** — Ariadne TPC-DS model + 4 curated queries + `pg-tpcds` connection (Arges reads
      `tpcds_readonly`, `requires-tenant-id=false`) + Kyklop routing. Then **drop arges/brontes fixture
      mode** and run the `tpcds-query` context (MP-2). `tasks-t2-model-connection.md`.

**Waves 3–7 (agents / domain / librarian / infra + FEs):**
- [ ] Author `clusters/bp-dsk/apps/*` for: themis, pythia, midas-core, midas-excel-loader,
      report-renderer, sysifos-bff, kleio, kallimachos, pinakes, kallimachos-mcp, hebe, whois, health,
      backstage + FEs sysifos/landing/kallimachos-browse.
- [ ] **DB-cred ClusterExternalSecrets + Azure KV keys** for the DB-backed ones: pythia
      (`pythia-db-credentials`), midas-core (`midas-db-secret`), kleio, whois — each: KV key +
      ClusterExternalSecret into `kantheon` + FQDN/db/user override (§3 pattern). backstage uses its
      own existing `backstage-postgres`.
- [ ] **prometheus real PG** — swap the H2 `test` profile for the default profile + wire
      `POSTGRESQL_HOST=postgres-rw.data.svc`, `PROMETHEUS_DATABASE=llm_gateway`,
      `PROMETHEUS_DB_USER/PASSWORD` (a `pg-llm-gateway` ClusterExternalSecret).
- [ ] **Keycloak realm clients** — none exist yet (realm has only argocd/grafana). Needed for the SPAs
      (iris/sysifos/landing/kallimachos-browse) + backstage.
- [ ] **charon named connections** — re-enable the registry ConfigMap + `charon-db-credentials` when
      the ERP/analytics DBs land.

**Housekeeping / merges:**
- [ ] **Merge kantheon `feat/t1-test-pg-load` → master** — carries the vendored TPC-DS DDL +
      `TpcdsLoadComponentSpec` + the WS-T1 runbook/task updates (not yet on master).
- [ ] **Merge kantheon `feat/d3-bp-dsk-apps` → master** — the D3 docs/task updates.
- [ ] **sync-wave ordering** isn't enforced (the appset sets none) — services crash-loop until deps
      are up, then self-heal converges. Add `argocd.argoproj.io/sync-wave` to the appset template if
      strict ordering is wanted.
- [ ] **Release-tag sweep** at MP-4 (contracts §9).

---

## 8. Repo / branch state (end of session)

- **kantheon `master`** — D1/D2 charts + all per-service **code** fixes (kyklop chart, mcp-wrapper
  charts + conf blocks, metis/kallimachos-mcp probes, the `mapOf`→`buildJsonObject` sweep + docs),
  the `validate-charts` helm-agnostic fix, the midas RLS test fix, tatrman 0.8.6.
- **kantheon `feat/d3-bp-dsk-apps`** — D3 docs (`d3-bring-up.md`, tasks) — **unmerged**.
- **kantheon `feat/t1-test-pg-load`** — TPC-DS DDL + `TpcdsLoadComponentSpec` + `t1-tpcds-load.md` —
  **unmerged**.
- **olymp `master`** — the full D3 estate (single-ns appset, waves 1–2 apps, platform-dep top-up) +
  all per-service **values** fixes (charon, prometheus, arges/brontes fixture, iris, chartRevision
  flips, the `charon-redis-url` ClusterExternalSecret) + the `test-pg` cluster/DB/creds + `tpcds-load`
  Job + the `tpcds-staging` bucket. **Merged / live.**
