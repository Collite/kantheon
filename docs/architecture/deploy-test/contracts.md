# Deployment & Testing — Contracts (kantheon × olymp)

> **What this is.** The normative interface spec for the deploy-test program ([`../../implementation/v1/deploy-test/master-plan.md`](../../implementation/v1/deploy-test/master-plan.md) is the plan + architecture). Everything below is a contract a task list builds *to*: chart values schemas, the ArgoCD app shape, platform-dep declarations, the TPC-DS warehouse + model + connection, the component/integration test surfaces, and the bp-dsk run mode. Grounded in the live repos (2026-06-27).
>
> **Source-of-truth split.** kantheon owns charts/images/specs/models; olymp owns ArgoCD apps, platform deps, the test-harness recipes, and test-contexts. The **only** value that crosses the repo boundary at test time is a **context name** (§6). At deploy time the boundary is **`config.json` → `chartPath`+`chartRevision`** (§2).

---

## 1. Helm library-chart contract (`shared/charts/kantheon-service`)

Today each module ships a standalone chart (`<module>/k8s/{Chart.yaml,values.yaml,templates/{_helpers.tpl,deployment.yaml,service.yaml}}`). The 18 existing ones are copy-variants of the same shape. **Contract:** extract that shape into one library chart and have every module's chart depend on it.

### 1.1 Library chart

`shared/charts/kantheon-service/Chart.yaml`:

```yaml
apiVersion: v2
name: kantheon-service
type: library          # library → emits no resources itself; consumed via `include`
version: 0.1.0
```

Templates (named templates, prefixed `kantheon-service.`):
- `kantheon-service.deployment` — Deployment (replicas, image, imagePullSecrets, ports, probes, resources, env from `extraEnv` + telemetry, OTel envs).
- `kantheon-service.service` — Service (ClusterIP; ports from `.Values.ports`).
- `kantheon-service.helpers` — name/labels/selectorLabels/serviceAccountName.
- Optional: `kantheon-service.httproute` (Envoy Gateway `HTTPRoute`, gated on `.Values.httpRoute.enabled`) for externally-exposed FEs.

### 1.2 Consuming (per-module) chart

Each deployable keeps `<module>/k8s/` (so olymp's `chartPath: <module>/k8s` is unchanged), now thin:

```yaml
# <module>/k8s/Chart.yaml
apiVersion: v2
name: <module>            # e.g. theseus, midas-core, arges
type: application
version: 0.1.0
appVersion: "dev"         # tracks the default image tag
dependencies:
  - name: kantheon-service
    version: 0.1.0
    repository: "file://../../../shared/charts/kantheon-service"   # relative path from the module
```

`<module>/k8s/templates/main.yaml` just renders the library templates:

```yaml
{{ include "kantheon-service.deployment" . }}
---
{{ include "kantheon-service.service" . }}
{{- if .Values.httpRoute.enabled }}
---
{{ include "kantheon-service.httproute" . }}
{{- end }}
```

> **Helm dependency note.** `file://` deps require `helm dependency build` before render. The kantheon nightly + olymp `infra-up` + ArgoCD multi-source all render with the chart checked out in-repo, so the relative path resolves. Add `helm dependency build` to the chart-validation `just` recipe (§1.5).

### 1.3 Values schema (the stable contract olymp/test-contexts override)

```yaml
replicaCount: 1
image:
  repository: <name>          # ghcr.io/boraperusic/<name> when published; bare <name> for local jib
  tag: ""                     # "" → .Chart.appVersion
  pullPolicy: IfNotPresent    # test contexts use Always (mutable :testing tag)
imagePullSecrets: []          # [{ name: ghcr-pull }] on bp-dsk / test clusters
ports:
  http: <port>                # container = service = probe port
  grpc: <port>                # omit for FE/HTTP-only modules
service:
  type: ClusterIP
resources:
  requests: { memory: …, cpu: … }
  limits:   { memory: …, cpu: … }
telemetry:
  enabled: false
  endpoint: ""                # e.g. http://alloy.observability.svc.cluster.local:4317
  serviceName: <name>
extraEnv: []                  # downstream wiring — REPLACED wholesale by the deploying env
readinessProbe: { path: /health, … }
livenessProbe:  { path: /health, … }
httpRoute:                    # FE/externally-exposed only
  enabled: false
  gateway: { name: eg, namespace: gateway }
  hostname: ""
```

**Invariant:** `extraEnv` is the **entire** downstream-wiring surface and is *replaced* (not merged) by an env's values (olymp confirmed this for test contexts). Chart defaults carry the constellation's stable in-cluster service names; an env overrides only to retarget (e.g. LLM upstream → WireMock, or `pg-tpcds` host → `test-pg`).

### 1.4 Variants

- **Kotlin/Ktor service/agent/worker** — the base template; `ports.http` + `ports.grpc`.
- **FE nginx** (`frontends/*`) — `ports.http` only, `httpRoute.enabled: true`, `config.*` block (BFF upstream, Keycloak) as in `frontends/iris/k8s/values.yaml`. A `kantheon-service.fe-configmap` named template renders the nginx env/`config.json`.
- **Python** (`services/{kadmos,metis,steropes…}`, `agents/pythia` if py) — identical Deployment/Service shape; the only difference is the image is built via `build-py` not Jib. No template change.

### 1.5 Migration contract (WS-D first task)

1. Extract `kantheon-service` from the current `services/theseus/k8s` templates (the reference).
2. Migrate the **18 existing charts** to depend on it; `helm template` output must be **byte-equivalent** to today's render (golden-file test in `just validate-charts`) — proves no regression before any new chart.
3. Then author the **22 new** module charts on top (WS-D waves).

---

## 2. ArgoCD app contract (olymp `clusters/bp-dsk/apps/<name>/`)

Adding a module to bp-dsk = one directory; `appset-apps` (`clusters/bp-dsk/appset-apps.yaml`, git-files generator over `clusters/bp-dsk/apps/*/config.json`) discovers it.

### 2.1 `config.json` (chart coordinates — the cross-repo deploy key)

```json
{ "chartPath": "<module>/k8s", "chartRevision": "main" }
```

- `chartPath` — path into the **kantheon** repo.
- `chartRevision` — git ref of the kantheon chart to render: a **branch** during bring-up (e.g. `feat/...`), flipped to **`main`** on merge (the iris precedent: `feat/p2-s2.3-session-ux` → `main`).

### 2.2 `values.yaml` (per-cluster config — olymp owns)

The §1.3 override surface: `image.repository` (`ghcr.io/boraperusic/<name>`), `image.tag` (the released tag), `imagePullSecrets: [{name: ghcr-pull}]`, `extraEnv` (in-cluster wiring), and for FEs `config.*` + `httpRoute.hostname`.

### 2.3 Generated Application (by the ApplicationSet — do not hand-author)

`project: platform-apps`, multi-source (chart ← kantheon@chartRevision, values ← olymp `$values/clusters/bp-dsk/apps/<name>/values.yaml`), `destination.namespace = <name>`, `syncPolicy.automated{prune,selfHeal}` + `CreateNamespace=true`.

### 2.4 Sync-wave ordering (deployment waves, master-plan WS-D)

Apps carry `argocd.argoproj.io/sync-wave` (via a values knob or an app-level annotation) so dependencies converge first: **platform (negative/0) → registry+core (1) → query path incl. Arges (2) → agents (3) → domain (4) → personal/librarian (5) → infra (6)**. Within the data plane, CNPG `Cluster` (wave 1) precedes `Database` CRDs (wave 2) — the existing convention.

### 2.5 Per-module deploy descriptor (kantheon → olymp handoff)

Each module declares, in its `<module>/k8s/README.md` (or a `deploy.md` stanza), what olymp must provision:

```
module: <name>
image: ghcr.io/boraperusic/<name>     # jib | fe-nginx | build-py
ports: { http: N, grpc: M }
needs:
  pg-database: <name>?                 # → CNPG databases.yaml + ExternalSecret cred
  seaweed-bucket: <name>?
  keycloak: { client: <id>?, serviceAccount: <id>? }
  downstream: [ <service names it calls> ]
wave: <n>
externally-exposed: { hostname: <host>? }   # → HTTPRoute on `eg`
```

This descriptor is the checklist for the olymp app PR.

---

## 3. Platform-dependency contract (olymp `platform/`)

| Dep | Where | Contract |
|---|---|---|
| **Agent Postgres DB** | `platform/data/postgres/base/databases.yaml` + `cluster.yaml managed.roles` + `overlays/bp-dsk/externalsecret-pg-<name>-cred.yaml` | one `Database` CRD (`owner: <name>`, `cluster: {name: postgres}`, sync-wave 2) + a managed login role + an `ExternalSecret` named `pg-<name>-cred`. Add for: midas, hebe, kleio (+ any agent reaching the shared PG). |
| **TPC-DS warehouse** | new `platform/data/test-pg/` (see §4) | a **separate** CNPG `Cluster` named `test-pg` — NOT the shared `postgres`. |
| **Seaweed bucket** | `platform/data/seaweed` | bucket per consumer (charon/kleio artifacts, **tpcds-staging** for the load Job). |
| **Keycloak client / SA** | `platform/auth/keycloak/overlays/bp-dsk` (realm-as-code, realm `kantheon`) | SPA client per FE (iris/sysifos/landing) with redirect URIs/web-origins for `<app>.192-168-1-38.nip.io`; service account per BFF/service needing OBO. |
| **ghcr-pull** | `clusters/bp-dsk/sys-image-updater/externalsecret-ghcr-pull.yaml` (ClusterExternalSecret) | the pull secret must land in each new app namespace (selector edit; "race-the-pod" gotcha — testing S3.3 T3). |

---

## 4. TPC-DS warehouse contracts

### 4.1 `test-pg` server + `tpc-ds-1g` database (olymp `platform/data/test-pg/`)

A dedicated CNPG `Cluster` (separate from the shared agent PG, master-plan §7-D1):

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata: { name: test-pg, namespace: data }
spec:
  instances: 1
  storage: { size: 8Gi }            # ~1.2 GB data + indexes + WAL headroom (SF1)
  managed:
    roles:
      - { name: tpcds,          ensure: present, login: true, passwordSecret: { name: pg-tpcds-cred } }       # owner/loader
      - { name: tpcds_readonly, ensure: present, login: true, passwordSecret: { name: pg-tpcds-ro-cred } }    # Arges read path
---
apiVersion: postgresql.cnpg.io/v1
kind: Database
metadata: { name: tpc-ds-1g, namespace: data, annotations: { argocd.argoproj.io/sync-wave: "2" } }
spec: { name: "tpc-ds-1g", owner: tpcds, cluster: { name: test-pg } }
```

`tpcds_readonly`: `GRANT CONNECT` + `GRANT USAGE ON SCHEMA public` + `GRANT SELECT ON ALL TABLES` + `ALTER DEFAULT PRIVILEGES … GRANT SELECT` (applied by the load Job's finalize step). This is the role Arges connects as (read-only, mirrors `midas_app_readonly`).

### 4.2 DDL set + data mapping (kantheon)

Vendor the two DDLs (the third, `tpcds_source.sql` = `s_*` ETL schema, is **not** used) into the repo, e.g. `deployment/tpcds/ddl/`:
- `tpcds.sql` — the **25 `CREATE TABLE`s** (24 warehouse + `dbgen_version`). Source of truth for the schema.
- `tpcds_ri.sql` — FK constraints; applied **after** load.

**Data:** 25 pipe-delimited `.dat` files (host `~/Data/TPC-DS`, SF1 ~1.2 GB). `<table>.dat` → table `<table>` (1:1 by filename).

**Trailing-pipe contract (load gotcha — must be tested):** every `.dat` row ends with a trailing `|`, so an N-column table emits N+1 fields. Postgres `COPY` rejects that. **Required handling (pick one, documented in the load task):**
- (a) For each table, `COPY` into a view/temp with a dummy trailing text column, then `INSERT … SELECT` the real columns; **or**
- (b) stream-strip the trailing `|` before `COPY FROM STDIN` (e.g. `sed 's/|$//'`).
A component test asserts a small fixture `.dat` with trailing pipes loads to the correct row/column count.

### 4.3 Seaweed staging + load Job (olymp)

- **Staging:** `.dat` files uploaded to Seaweed bucket `tpcds-staging` (one-time, from host; documented `mc cp`/`aws s3 cp` step). The cluster never needs host access for reloads.
- **Load Job:** a Kubernetes `Job` `tpcds-load` in ns `data`:
  1. `psql -f tpcds.sql` against `tpc-ds-1g` (create tables; idempotent `DROP … IF EXISTS` guard or `TRUNCATE`).
  2. For each of the 24 tables: fetch `<table>.dat` from Seaweed → trailing-pipe-safe `COPY` (§4.2).
  3. `psql -f tpcds_ri.sql` (FKs) + `ANALYZE`.
  4. Grant `tpcds_readonly` (§4.1).
  Re-runnable (truncate-reload). Readiness for contexts = `Job tpcds-load Complete`.

### 4.4 Ariadne model + curated queries (kantheon `ai-models`)

- **Model:** a TPC-DS model over the `tpc-ds-1g` schema (`model-ttr`), enough entities/joins for the curated queries (the star: `store_sales`/`catalog_sales`/`web_sales` facts + `date_dim`/`item`/`customer`/`store` dims).
- **Area:** `tpcds` (`model-ttr/areas/tpcds.ttrm`) → its packages (mirrors `ucetnictvi`/`investment`).
- **Curated query set (master-plan §7-D2 — "stress Proteus a little"), authored as Ariadne queries on connection `pg-tpcds`:**

| id | shape | sketch |
|---|---|---|
| `q.tpcds.store_sales_by_month` | join + group-by aggregation | `store_sales ⋈ date_dim` → `SUM(ss_sales_price)` GROUP BY `d_year,d_moy` |
| `q.tpcds.top_items_by_revenue` | join + agg + ORDER/LIMIT | `store_sales ⋈ item` → top-N `i_item_id` by `SUM(ss_net_paid)` |
| `q.tpcds.customer_running_total` | **window function** | `SUM(...) OVER (PARTITION BY c_customer_sk ORDER BY d_date)` |
| `q.tpcds.channel_revenue_cte` | **CTE** + union/agg across channels | `WITH s AS (...store...), c AS (...catalog...), w AS (...web...) SELECT ... ` |

Each query uses `{name}`-style params where parameterised (Proteus parameter-bridge rewrites `{name}→?`). These are the Proteus-PG-unparse exercise (must emit valid PostgreSQL — extends the Arges S1.2 follow-on).

### 4.5 `pg-tpcds` connection profile (kantheon)

Arges named-connection (HOCON `connections { … }`, the `pg-midas` pattern in `workers/arges`):

```hocon
connections {
  pg-tpcds {
    engine = "postgres"
    host = ${?ARGES_PG_TPCDS_HOST}        # → test-pg.data.svc.cluster.local
    port = 5432
    database = "tpc-ds-1g"
    user = ${?ARGES_PG_TPCDS_USER}        # → tpcds_readonly
    password = ${?ARGES_PG_TPCDS_PASSWORD}
    read-only = true
    requires-tenant-id = false            # TPC-DS is not multi-tenant — no SET LOCAL app.tenant_id
  }
}
```

- Chart `extraEnv` supplies `ARGES_PG_TPCDS_{HOST,USER,PASSWORD}` (password from the `pg-tpcds-ro-cred` ExternalSecret).
- **Kyklop** routes `connection_id = pg-tpcds` → Arges (its dispatch map must learn the profile).
- `requires-tenant-id = false` → Arges skips the RLS envelope for this connection (contrast `pg-midas`).

---

## 5. Component-tier test contract (kantheon, Testcontainers, no cluster)

Existing plumbing (testing Phase 1): `componentTest` source set, `@Tag("component")`, `just test-component`, and `shared/libs/kotlin/component-testkit` — `Containers.{postgres(),mssql(),wiremock()}`, `WireMockAdmin`, `SqlScripts`, `CiOnly`. Specs live in `<module>/src/componentTest/kotlin/…`.

**Contract — the real-dep matrix to fill (each = one spec, TDD-first):**

| Spec | Module | Real dep | Seam asserted |
|---|---|---|---|
| `ArgesPostgresComponentSpec` ✓ / `RlsLeakageComponentSpec` ✓ / `TpcdsLoadComponentSpec` ✓ / `ArgesNoRlsTpcdsComponentSpec` ✓ | workers/arges | `Containers.postgres()` + TPC-DS subset seed | type mapper (`numeric(20,4)` boundary) + `COPY`-trailing-pipe + read-only + `SET LOCAL` RLS (leakage + fail-closed) **and** the `requires-tenant-id=false` `pg-tpcds` no-RLS path → Arrow |
| `BrontesMssqlComponentSpec` ✓ | workers/brontes | `Containers.mssql()` (CI-only) | (exists) Proteus-SQL → MSSQL → result shape |
| `CharonPostgresComponentSpec` (deferred) | services/charon | postgres | DB extract→Arrow→ingest (lands with Charon DB edges) |
| `ProteusUnparseComponentSpec` ✓ | services/proteus | none (golden SQL) | RelNode → **PostgreSQL** for the 4 TPC-DS shapes + named-param `{name}→?` (golden files under `resources/proteus/`; `RECORD_GOLDEN=1` regenerates) |
| `ArgosRlsComponentSpec` ✓ | services/argos | `Containers.postgres()` | `PolicyEngine` tenant-isolation predicate **enforced** on real PG — cross-tenant denial + same-tenant admit, driven by Argos's emitted column+literal |
| `AriadneModelLoadComponentSpec` ✓ | services/ariadne | none/classpath | reconcile the **real bundled `model-ttr/`** — `ListQueries` (4 TPC-DS curated) + `ResolveArea(tpcds)`/`ResolveArea(accounting)`. **investment deferred** (model unauthored — Midas arc) |
| `MidasRepositoryComponentSpec` ✓ / `RlsLeakageComponentSpec` ✓ | agents/midas/core | postgres | (exist) repo round-trip + cross-tenant RLS |
| `ReportRendererComponentSpec` ✓ | services/report-renderer | none (real POI) | **XLSX** render: scalar fill + table-region expansion + style preservation + valid OOXML. **PPTX/PDF/HTML deferred** (engines + Playwright not wired; no vendored templates) |
| `KleioPgvectorComponentSpec` (deferred) | services/kallimachos | postgres+pgvector/AGE | vector + graph plane |
| `HebePgMemoryComponentSpec` (deferred) | agents/hebe | postgres | PG MemoryStore RRF parity |
| `PrometheusGatewayComponentSpec` (deferred) | services/prometheus | `Containers.wiremock()` | LLM upstream stub round-trip + cost capture — **deferred to Prometheus's separate Spring integration suite** (module build policy keeps WireMock/Testcontainers/SpringBootTest out of its in-module tiers; Spring AI 2.0.0-M2's official Anthropic SDK + full-context deps (PG/Redis/OAuth2/gRPC) make an in-tier hermetic stub inappropriate) |

**Invariants:** `just test-all` collects **zero** `@Tag("component")` (planning-conventions §4 mocked-only gate; enforced structurally by the source-set split + the `test`-classpath leak guard in the root `build.gradle.kts`); component specs run in CI on every PR; image pins are fixed tags (no `latest`). MSSQL specs are **CI-only** (arm64 qemu segfaults — `CiOnly`); the C1 landed specs are all Postgres/no-container, so none needs the gate.

**C1 status — DONE, MP-3 closed (2026-07-09):** T1–T5 + T7 landed — 7 green specs across arges/proteus/argos/ariadne/report-renderer, run via the CI `test-component` step on **every PR + merge** (master green). The `test-all` mocked-only gate still collects zero `@Tag("component")` (structural leak guard). T6 (Prometheus) + the Charon/Kleio/Hebe rows stay deferred as arc follow-ups (not MP-3 blockers). The two scope deviations (investment model, PPTX/PDF/HTML) are unauthored/unwired surfaces, tracked in the row notes. CI wrinkle fixed post-landing: `AriadneModelLoadComponentSpec` now reads the bundled model from the authored resources dir on disk (was a `jar:` classpath URI → `FileSystemNotFoundException` on CI, where the module rides the suite classpath as a JAR).

---

## 6. Integration-tier test contract (kantheon specs × olymp contexts)

### 6.1 The cross-repo key

kantheon `@RequiresContext("<name>")` ⇔ olymp `test-contexts/<name>/context.yaml metadata.name`. **`ContextNameRegistrySpec`** (component-tier) asserts every `@RequiresContext` string has a matching `test-contexts/<name>/` in a checked-out olymp. **The name is the only thing that crosses.**

### 6.2 olymp `context.yaml` schema (`olymp.collite/v1 TestContext`)

```yaml
apiVersion: olymp.collite/v1
kind: TestContext
metadata: { name: <name>, description: … }
services:                       # Helm charts from kantheon@chartRevision
  - { name: <svc>, chartPath: <module>/k8s, chartRevision: testing, values: <svc>.values.yaml }
platform:                       # olymp platform/ deps (kubectl-applied, no ArgoCD)
  - <mssql | test-pg | wiremock | seaweed | …>
readiness:                      # infra-up waits on these before handing off
  - { kind: Deployment|StatefulSet|Job, name: <wl>, condition: Available|Complete }
```

Per-service `<svc>.values.yaml` = the §1.3 override surface with `image.tag: testing`, `pullPolicy: Always`, and `extraEnv` retargeting downstream wiring into the run namespace.

### 6.3 The new `tpcds-query` context (the Goals 2+4 showcase)

```yaml
metadata: { name: tpcds-query }
services:                       # theseus query chain on the PG worker
  - theseus, theseus-mcp, proteus, argos, kyklop
  - { name: arges, chartPath: workers/arges/k8s, chartRevision: testing, values: arges.values.yaml }   # extraEnv → pg-tpcds → test-pg
  - { name: ariadne, … }        # serves the TPC-DS model + curated queries
platform:
  - test-pg                     # the test-pg cluster + tpc-ds-1g (or a context-scoped equivalent)
  - tpcds-load                  # the load Job (readiness: Complete) — OR assume a pre-loaded standing test-pg
readiness:
  - { kind: Job, name: tpcds-load, condition: Complete }
  - { kind: Deployment, name: arges, condition: Available }
  - …theseus chain…
```

kantheon spec `TpcdsQueryIntegrationSpec @RequiresContext("tpcds-query")`: drive each curated query through theseus-mcp `query` → assert row counts / a known aggregate value from the SF1 data (the deterministic oracle).

### 6.4 Run-set

`theseus-runquery` (✓ live), `golem-erp`, `themis-routing`, `pythia-rca`, **`tpcds-query`** (new), plus the deploy-smoke graduations `iris-session` / `sysifos-workbench`. `nightly.txt` lists the nightly members (bp-olymp01); bp-dsk runs the full set on demand (§7).

### 6.5 WireMock fixtures

External egress (LLM upstream via Prometheus, modeler TTR) is stubbed by the in-cluster `wiremock` platform member; fixtures loaded at runtime via `WireMockAdmin` (the §5 helper retargeted). The pure `query`/`tpcds-query` paths make **no** external HTTP calls, so they need no WireMock fixtures (the theseus-runquery premise-correction).

---

## 7. bp-dsk run-mode contract (Workstream R)

**Goal:** run the integration set on **bp-dsk** (local) on demand; **nightly stays on bp-olymp01** (§7-D4).

### 7.1 olymp `infra-up`/`infra-down` `--kube dsk`

`just infra-up <context> <run-id> dsk` (the existing recipe signature `infra-up context run-id kube *FLAGS`):
1. resolve `test-contexts/<context>/`;
2. create run namespace `kantheon-<context>-<run-id>` (labelled `olymp.collite/context` + `olymp.collite/run` + `olymp.collite/managed-by=test-harness`);
3. apply `platform[]` deps + helm-install `services[]` (charts from the kantheon checkout under test);
4. wait on `readiness[]`;
5. emit the namespace handshake (sole stdout line: `namespace=kantheon-<context>-<run-id>`; all logs → stderr).
`infra-down <context> <run-id> dsk` deletes the namespace (always; `trap`).

### 7.2 The reconcile-boundary invariant (hard pre-task)

bp-dsk runs ArgoCD with `appset-apps` globbing `clusters/bp-dsk/apps/*` and `appset-ops` globbing `clusters/bp-dsk/platform/*` (git-files/git-dir generators over **repo paths**, not namespaces). **Neither may produce an Application for `test-contexts/` nor for the `kantheon-<context>-<run-id>` run namespaces** — ArgoCD must not reconcile/prune scripted test resources. **Contract:** the run namespace + its `olymp.collite/*` labels are outside every appset generator's scope; verify on bp-dsk exactly as test-harness §8/§9 verifies on bp-olymp01 (a deliberately-created `kantheon-*` run ns is left untouched by ArgoCD). This verification is the **first WS-R task** and gates everything else on bp-dsk.

### 7.3 kantheon Gradle wiring

`./gradlew :integrationTest -Pcontext=<name> -Pnamespace=kantheon-<name>-<run-id>` (from the `infra-up` handshake). The `@RequiresContext` gate resolves the namespace (prop or `olymp.collite/context` label), asserts readiness (read-only k8s: get Deployment/Endpoint status — no apply/delete from the kantheon side), injects `ContextHandle` (in-cluster URLs + WireMock admin base).

### 7.4 Local k3d parity (the TDD "run locally" leg)

The same `infra-up <context> <run-id> <k3d-ctx>` runs against a throwaway k3d cluster (the fork path, test-harness §9.3). The TDD loop (master-plan §6): **write spec → run on k3d (or Testcontainers) → run on bp-dsk**. A spec is DONE only when green in both.

---

## 8. Image-publishing contract

| Kind | Recipe | Image | Arch |
|---|---|---|---|
| Kotlin (Jib) | `just publish-image <module> [tag]` | `ghcr.io/boraperusic/<name>:<tag>` | multi-arch (amd64+arm64) when `CI=true` |
| FE nginx | `just publish-fe-image <service> <tag>` | `ghcr.io/boraperusic/<service>:<tag>` | linux/amd64 |
| Python | `just build-py <service>` + `docker push` | `ghcr.io/boraperusic/<name>:<tag>` | per Dockerfile |

**Tag lifecycle:** `:testing` (mutable, `pullPolicy: Always`) for integration contexts; `:vX.Y.Z` (immutable, `pullPolicy: IfNotPresent`) for bp-dsk live apps. `image.repository` in values matches the chart's `<name>` (the deployment name). bp-dsk live apps pin `image.tag` to a released tag + `config.json chartRevision: main`.

---

## 9. Release-tag sweep contract (MP-4, §7-D6)

Crossing MP-4 (integration green on bp-dsk) cuts the deferred v1 tags (from [`../../implementation/v1/master-plan.md`](../../implementation/v1/master-plan.md) §7): `pythia/v0.1.0…v1.0.0`, `envelope-render/v0.1.0` + `golem/*` (+ the `golem/v1.0.0` cutover), `iris/*` + `iris-bff/*`, `sysifos*`, `arges/v0.1.0`, `hebe/v0.3.0`+`v0.4.0` + `capabilities-mcp/v0.2.0`, and Fork-P5 (`whois`/`health`/`backstage`/`v0.1.0`, `argos/v0.2.0`). Each tag coordinates with `gradle/libs.versions.toml`.

---

*Contracts created 2026-06-27. Owner: Bora. Pairs with the master plan (architecture) + olymp `docs/test-harness.md`. Task lists build to these contracts.*
