# Deployment & Testing — Cross-repo Master Plan (kantheon × olymp)

> **What this is.** The program-level plan that takes the v1 constellation from "code-complete + a 4-app bp-dsk footprint" to "**full constellation live on bp-dsk, a TPC-DS test database loaded, a comprehensive component + integration test suite, and integration tests runnable on bp-dsk.**" It spans **two repos** — `kantheon` (services, charts, images, specs, models) and `olymp` (ArgoCD GitOps, platform deps, test-harness, contexts). It orchestrates and extends the existing [`testing/plan.md`](../testing/plan.md) (Stream T) and olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md) / `docs/plan.md` Phase 7 — it does **not** restate their stage detail.
>
> **Status.** **Master plan — §7 decisions resolved 2026-06-27.** This defines the structure; the three per-arc artefacts (architecture / contracts / detailed task lists) are the next step now that §7 is settled. Every task list spawned from this plan is **TDD-first**: the Coding Agent **writes the tests first, then runs them locally, then on bp-dsk** (§6).
>
> **Owner: Bora.** Companions to be created: `architecture.md` + `contracts.md` under `docs/architecture/deploy-test/` (kantheon) and mirrored entries in olymp `docs/`.

---

## 1. Goals → workstreams

The four goals map to four workstreams that run partly in parallel and converge on a single "green on bp-dsk" finish line.

| # | Goal | Workstream | Primary repo | Depends on |
|---|---|---|---|---|
| 1 | Deploy everything on bp-dsk | **D — Full-constellation deployment** | both (charts+images: kantheon · ArgoCD apps+platform: olymp) | charts authored; images published |
| 2 | TPC-DS database on bp-dsk (Postgres/Arges) | **T — TPC-DS test warehouse** | both (DDL+model: kantheon · CNPG db + load Job: olymp) | central PG live (✓); Arges deployed (D) |
| 3 | Comprehensive component + integration tests | **C — Test suite** | kantheon (specs+fixtures) · olymp (contexts) | extends `testing/` arc |
| 4 | Run integration tests on bp-dsk | **R — bp-dsk run mode** | olymp (harness) · kantheon (workflow) | C; D (services live); harness Phase A (✓) |

**The finish line (program DONE):** the full constellation is reconciled on bp-dsk (landing included; backstage + kallimachos-browse best-effort); the `tpc-ds-1g` Postgres warehouse on the dedicated **`test-pg`** server is queryable through the live query path (theseus→proteus→argos→kyklop→**arges**); the component suite is green in CI on every PR; the integration run-set (incl. a new **`tpcds-query`** context) runs green **on bp-dsk** (a local-run target; nightly stays on bp-olymp01); and the deferred v1 release tags (§7-D6) are cut.

---

## 2. Where we are (2026-06-27 baseline)

**kantheon.** All product code complete except Midas P3 (see [`../master-plan.md`](../master-plan.md)). **18 modules have a Helm chart** (`*/k8s/Chart.yaml`); **~22 deployables are chartless** — `agents/{hebe,kleio,midas,pythia}`, `agents/sysifos-bff`, `services/{charon,metis,report-renderer,kallimachos,pinakes}`, `workers/steropes`, all `tools/*-mcp` except theseus/capabilities, `infra/backstage`, `frontends/{landing,sysifos,kallimachos-browse}`. Image publishing recipes exist (`just publish-image` Jib→GHCR multi-arch; `just publish-fe-image` nginx→GHCR; `build-py` for Python). Component tier (`componentTest`) + integration harness (`integrationTest`, `@RequiresContext`) already landed (testing Phase 1 done; Phase 2 harness done).

**olymp.** ArgoCD GitOps for bp-dsk (primary K3s cluster, full ArgoCD). **bp-dsk runs 4 apps:** `capabilities-mcp`, `golem`, `iris`, `iris-bff`. Adding an app = a folder `clusters/bp-dsk/apps/<name>/{config.json,values.yaml}` (`config.json` → `chartPath` into the kantheon repo + `chartRevision`); `appset-apps` discovers it. Platform layer live: **CNPG `postgres`** (one DB per agent — iris/pythia/golem/whois/keycloak/llm-gateway via `databases.yaml` + per-cluster `ExternalSecret` creds), **MSSQL**, **Redis**, **Seaweed**, monitoring (Loki/Tempo/Grafana/Alloy/Prometheus), ESO, cert-manager, image-updater. Test-harness Phase A done — `infra-up/down <context>` recipes + the `theseus-runquery` context **LIVE-verified on bp-olymp01**; contexts `golem-erp` (draft), `themis-routing` exist; nightly target is **bp-olymp01** (Azure) or **k3d** (forks) — **not bp-dsk yet** (Goal 4 adds it).

---

## 3. The workstreams in detail

### Workstream D — Full-constellation deployment on bp-dsk

Bring every v1 module under ArgoCD on bp-dsk, wave-ordered by dependency.

**kantheon side (per chartless module):**
- Author a Helm chart `<module>/k8s/` mirroring an existing one (Kotlin svc → copy `services/theseus`; FE nginx → copy `frontends/iris`; Python → `services/kadmos` pattern). Deployment/Service/values, `image.repository`, health/ready probes, env, resources (architecture §13 shapes).
- Publish the image to GHCR (`just publish-image`/`publish-fe-image`/`build-py` + push), multi-arch where the node needs it.
- Declare required platform deps (PG database name, Seaweed bucket, Keycloak client/SA, config) so olymp can wire them.

**olymp side (per module):**
- `clusters/bp-dsk/apps/<name>/{config.json,values.yaml}` → ApplicationSet discovers it.
- Platform deps: add the agent's DB to `platform/data/postgres/base/databases.yaml` + a bp-dsk `ExternalSecret` cred (midas, hebe, kleio, …); Seaweed buckets; Keycloak realm clients + service accounts (kantheon realm); `ghcr-pull` secret reachable in the new namespace.
- Sync-wave ordering so dependencies come up first.

**Deployment waves (proposed; finalize in §7):**
1. **Platform deps** — CNPG databases (add midas/hebe/kleio/…), MSSQL, Seaweed buckets, Keycloak clients/SAs, ESO creds. *(mostly exists; extend.)*
2. **Registry + core services** — `capabilities-mcp` (✓), `ariadne`, `prometheus`, `echo`, `kadmos` (+ their `*-mcp` wrappers).
3. **Query path** — `proteus`, `argos`, `kyklop`, `theseus` (+ `theseus-mcp`), workers `brontes` / `arges` / `steropes`.
4. **Agents** — `themis`, `golem` (✓), `pythia`, `iris-bff` (✓), `iris` (✓).
5. **Domain** — `midas-core`, `loaders/excel`, `report-renderer`, `golem-investment`, `sysifos-bff`, `frontends/sysifos`.
6. **Personal / librarian** — `hebe`, `kleio` (+ `kallimachos`, `pinakes`, `kallimachos-mcp`, `frontends/kallimachos-browse`).
7. **Infra** — `whois`, `health`, `frontends/landing` (in scope, must work); `backstage` + `frontends/kallimachos-browse` (**best-effort** — bring up after the core is green; don't gate the program on them).

**Reuses** the existing Stream-T deploy/smoke stages **S3.3 (Iris)** and **S3.4 (Sysifos)** as the *pattern* — each new module gets a GitOps-sync + smoke confirmation, not a bespoke deploy.

**Chart authoring approach (resolved — §7-D5): a shared Helm library chart.** Rather than copy-pasting ~22 near-identical charts, introduce **one Helm library chart** in kantheon (e.g. `shared/charts/kantheon-service`) capturing the common Kotlin/Ktor service shape (Deployment + Service + probes + `image.*` + env + resources + OTel). Each module keeps its per-module `k8s/Chart.yaml` (so olymp's `chartPath: <module>/k8s` convention is unchanged) but declares the library as a **dependency** and supplies only its values + overrides. Two thin sibling templates cover the **FE nginx** shape (mirror `frontends/iris`) and the **Python** shape (mirror `services/kadmos`); **workers** parametrise the service template. This is DRY, keeps the chartPath contract, and means a probe/label fix lands once. *(First task of WS-D: extract the library chart + migrate the 18 existing charts to it as the reference, then author the 22 new ones on top.)*

### Workstream T — TPC-DS test warehouse (dedicated `test-pg`, queried via Arges)

Stand up a **dedicated `test-pg` Postgres server** on bp-dsk (separate from the central CNPG agent DB) whose first database is **`tpc-ds-1g`**, loaded from the generated **SF1** data, and reachable through the live query path via Arges.

**The data (confirmed 2026-06-27).** Host path **`~/Data/TPC-DS`** — 25 pipe-delimited `.dat` files (24 warehouse tables + `dbgen_version`), **~1.2 GB (SF1)**, plus three DDLs: **`tpcds.sql`** (the 25 `CREATE TABLE`s — use this), **`tpcds_ri.sql`** (FK constraints — apply after load), **`tpcds_source.sql`** (the `s_*` ETL source schema — **not used**). **Gotcha:** TPC-DS `.dat` rows have a **trailing `|`**, so a 24-column table emits 25 fields — Postgres `COPY` must handle it (append a dummy trailing column per table, or strip the trailing pipe on the way in). Bake this into the load task + a test.

**kantheon side:**
- **TPC-DS DDL set** — vendor `tpcds.sql` + `tpcds_ri.sql` into the repo as a versioned SQL set for `tpc-ds-1g` (the `.dat`↔table mapping; the trailing-pipe-safe load form). Define the DB owner role + a **read-only role** for Arges (mirrors `midas_app_readonly`).
- **Ariadne model** over the TPC-DS schema (`ai-models` `model-ttr`) so Proteus/theseus resolve TPC-DS entities; a **`tpcds` area** + a **curated query set** — a few basic TPC-DS queries chosen to **stress Proteus a little** (join + group-by aggregation + a window function + a CTE), authored as curated queries (free-SQL through theseus also exercised).
- **Connection profile** `pg-tpcds` (→ the `test-pg` server) registered for Kyklop dispatch → Arges.
- **Proteus PG-unparse** must cover those TPC-DS query shapes (extends the Arges S1.2 follow-on).

**olymp side:**
- A **dedicated `test-pg` server** in the data plane (its own CNPG `Cluster` or StatefulSet, bp-dsk overlay + `ExternalSecret` cred) — **not** the shared agent CNPG — with database **`tpc-ds-1g`**. Sized for ~1.2 GB + indexes (PVC ≥ 5 GiB headroom).
- **Data staging + load Job** — stage the `.dat` files to a **Seaweed/S3 bucket** (the reusable path), then a Kubernetes **Job** runs `tpcds.sql` → trailing-pipe-safe `COPY FROM` per table → `tpcds_ri.sql`. Idempotent (drop/recreate or truncate-reload). *(Alternative for the one-off: a pre-populated PVC; Seaweed is preferred so reloads need no host access.)*
- Wire the `pg-tpcds` connection into the `tpcds-query` context (and expose `test-pg` to Arges on bp-dsk).

### Workstream C — Comprehensive component + integration test suite

Extends `testing/` Phases 1–3. **TDD-first** (§6).

**Component tier (kantheon, Testcontainers, no cluster) — broaden from Brontes/Charon to the full real-dep matrix:**
- `Arges ↔ Postgres` (incl. a TPC-DS subset seed) — type mapper + RLS (`SET LOCAL app.tenant_id`).
- `Proteus` golden-SQL (Calcite translate; add TPC-DS query golden outputs).
- `Argos` RLS/policy against real PG/MSSQL.
- `Ariadne` model load (TPC-DS + ucetnictvi/investment), `ListQueries`/`ResolveArea`.
- `Kyklop` dispatch routing; `Echo`/`Kadmos` real-dep where applicable.
- `Midas-core` repos/RLS/MV (mostly exists — S1.x), `report-renderer` POI/Playwright render, `Kleio` pgvector/AGE, `Hebe` PG memory.
- `Prometheus` gateway (WireMock upstream).

**Integration tier (contexts; kantheon specs+fixtures + olymp context.yaml):**
- Finish/green the existing: `theseus-runquery` (re-enable result/RLS asserts), `golem-erp`, `themis-routing`, `pythia-rca`.
- **New `tpcds-query` context** — theseus→proteus→argos→kyklop→**arges**→`tpcds` PG; assert TPC-DS query results (the showcase for Goals 2+4).
- Domain/UX contexts: `midas-investment` (Golem-Investment Q&A), `iris-session` (graduate S3.3 smoke), `sysifos-workbench` (graduate S3.4 smoke).

### Workstream R — Run integration tests on bp-dsk

Today the harness runs on bp-olymp01 (nightly) / k3d (forks). Add **bp-dsk** as a first-class **local** run target. **Nightly stays on bp-olymp01** (the Azure primary testing cluster, resolved §7-D4) — bp-dsk is the local machine where Bora runs the full set on demand, **not** the scheduled nightly. So WS-R is "make `--kube dsk` work + respect the live-cluster boundary," not "move the nightly."

**olymp side:**
- `infra-up/down --kube dsk` with **namespace-per-run isolation that respects bp-dsk's ArgoCD reconcile boundary** (the appset globs `clusters/bp-dsk/...` must not capture `test-contexts/` or run namespaces — verify it holds on bp-dsk as it does on bp-olymp01, test-harness §8/§9).
- Ensure the platform `_test` overlays + WireMock component + `ghcr-pull` copy work on bp-dsk.

**kantheon side:**
- The integration workflow / a `just` target parametrised to point `-Pcontext`/`-Pnamespace` at a bp-dsk `infra-up` run.
- The **local→bp-dsk loop** the TDD directive requires (§6): run a context's specs against a local k3d first, then the same against bp-dsk.

---

## 4. Cross-repo split (who owns what)

| Concern | kantheon | olymp |
|---|---|---|
| Service code, **Helm charts**, images | ✔ author charts + publish images | consume `chartPath`+`chartRevision` |
| ArgoCD apps / app-of-apps / waves | — | ✔ `clusters/bp-dsk/apps/*` |
| Platform deps (CNPG dbs, MSSQL, Seaweed, Keycloak, ESO) | declare requirements | ✔ provision |
| **TPC-DS** | ✔ DDL set, Ariadne model, curated queries, `pg-tpcds` profile, read-only role | ✔ dedicated `test-pg` server + `tpc-ds-1g` db + Seaweed staging + COPY-load Job |
| **Component specs** | ✔ all of it | — |
| **Integration specs + fixtures** | ✔ specs, WireMock fixtures, `@RequiresContext` names | ✔ `test-contexts/<name>/{context.yaml,*.values.yaml}` |
| **Harness** (`infra-up/down`, isolation, reaper, registry) | the integration point + verification | ✔ recipes + cluster modes |
| **bp-dsk run mode** | workflow points at bp-dsk | ✔ `--kube dsk` + boundary verification |
| Nightly / release-gate workflow | ✔ `integration-nightly.yml` | provides recipes |

**Naming/contract discipline:** every `@RequiresContext("x")` in kantheon must have a `test-contexts/x/` in olymp — guarded by `ContextNameRegistrySpec` (already exists). New contexts add a row both sides.

---

## 5. Sequencing & mergepoints

```
WS-D deploy ──(platform)──(core)──(query path: incl. ARGES live)──(agents)──(domain)──(infra)── full estate live
                                         │
WS-T tpcds ──── DDL+model+queries (kantheon) ──┴── tpcds CNPG db + COPY-load Job (olymp) ── warehouse queryable
                                                                    │
WS-C tests ── component matrix (now, no cluster) ── integration specs+fixtures (write ahead) ──┤
                                                                    │
WS-R bp-dsk ──────────── infra-up --kube dsk + boundary verify ────┴── run-set GREEN on bp-dsk ── PROGRAM DONE
```

- **MP-1 — Query path live on bp-dsk.** WS-D waves 1–3 done (esp. **Arges** + theseus + proteus + argos + kyklop). Unblocks WS-T load target and the `tpcds-query`/`theseus-runquery` contexts on bp-dsk.
- **MP-2 — TPC-DS queryable.** WS-T load Job green + Ariadne model + curated queries resolve → a manual `theseus query` against `pg-tpcds` returns rows.
- **MP-3 — Component suite comprehensive.** WS-C component matrix green in CI (no cluster needed — startable **now**, in parallel with everything).
- **MP-4 — Integration green on bp-dsk + release tags cut.** WS-R: the run-set (incl. `tpcds-query`) runs green via `infra-up --kube dsk`; then cut the deferred v1 release tags (§7-D6). **Program DONE.**

**Parallelism:** WS-C component tier and the WS-T kantheon-side DDL/model/queries need no cluster and start immediately. WS-D and the olymp-side WS-T/WS-R need bp-dsk. WS-R's `tpcds-query` context is the natural convergence demo.

---

## 6. TDD-first task-list directive (applies to every spawned task list)

Per the request, every task list this plan spawns instructs the Coding Agent to, **in order**:

1. **Write the tests first.** Component specs (Kotest + Testcontainers) and/or integration specs (`@RequiresContext`, WireMock fixtures) **before** the implementation/wiring they verify. Define expected results explicitly.
2. **Run them locally.** Component: `just test-component`. Integration: against a **local k3d** context via `infra-up --kube <k3d>` (the fork path), or Testcontainers where no cluster is needed.
3. **Run them on bp-dsk.** Deploy/sync the needed services on bp-dsk (WS-D), then run the same specs via `infra-up --kube dsk` → assert green on the live cluster.

A task is DONE only when its specs are green **both locally and on bp-dsk** (where a cluster is required). Stage gates still honour planning-conventions §4 (mocked unit tests gate merges); the component/integration greens are the additional, explicit deliverables this program is about.

---

## 7. Resolved decisions (2026-06-27)

- **D1 — TPC-DS scale + staging.** **SF1, ~1.2 GB**, at host `~/Data/TPC-DS` (25 `.dat` + 3 DDLs). A **dedicated `test-pg` server** (not the shared CNPG), first database **`tpc-ds-1g`**. Data staged to **Seaweed/S3 → `COPY FROM`** via an idempotent load Job. Trailing-pipe handling is a load task + test. *(WS-T.)*
- **D2 — TPC-DS query coverage.** Start with **a few basic queries that stress Proteus a little** — one each of: multi-table join, group-by aggregation, window function, CTE. Authored as Ariadne curated queries; free-SQL through theseus also exercised. Grow later. *(WS-T / WS-C `tpcds-query` context.)*
- **D3 — Deployment completeness.** Full constellation is the target; **`landing` is in scope (must work)**; **`backstage` + `frontends/kallimachos-browse` are best-effort** and do not gate the program. *(WS-D wave 7.)*
- **D4 — bp-dsk run mode.** **Nightly stays on bp-olymp01** (Azure primary testing cluster). **bp-dsk is the local on-demand full-run target** — WS-R makes `infra-up --kube dsk` work with the live-cluster reconcile-boundary respected (hard pre-task), it does **not** move the nightly. *(WS-R.)*
- **D5 — Chart authoring.** **Shared Helm library chart** (`shared/charts/kantheon-service`) + thin per-module charts that depend on it (keeps the `chartPath: <module>/k8s` contract); FE-nginx + Python sibling templates; workers parametrise the service template. Migrate the 18 existing charts to it first as the reference, then author the 22 new ones. *(WS-D first task.)*
- **D6 — Release-tag coupling.** **Yes** — crossing MP-4 also cuts the deferred v1 release tags from [`../master-plan.md`](../master-plan.md) §7 (pythia/golem+envelope-render/iris/sysifos/arges/hebe-P3+P4+cap-mcp-v0.2.0/fork-P5). The tag-sweep is folded into this program's finish line.

---

## 8. Proposed deliverable structure (after §7)

Once the decisions land, the detailed artefacts:

- **kantheon** `docs/architecture/deploy-test/architecture.md` + `contracts.md` (chart conventions, TPC-DS schema + model + connection contract, context registry additions, bp-dsk run contract).
- **kantheon** task lists under `docs/implementation/v1/deploy-test/`: `tasks-d-*` (charts+images+waves), `tasks-t-*` (TPC-DS DDL/model/queries), `tasks-c-*` (component matrix + integration specs), `tasks-r-*` (bp-dsk run mode) — each TDD-first per §6, 6–8 tasks.
- **olymp** mirrored task lists under its `docs/` (apps+platform, `tpcds` db + load Job, `--kube dsk` harness, contexts) — extending olymp `docs/plan.md` Phase 7 / `test-harness.md`.
- Update [`../master-plan.md`](../master-plan.md) §6 status board + olymp `docs/test-harness.md` cross-repo table as workstreams close.

---

*Master plan created 2026-06-27, §7 resolved same day. Owner: Bora. Next step: generate the architecture/contracts + the TDD-first task lists in both repos (per §8).*
