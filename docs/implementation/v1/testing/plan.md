# Testing — Implementation Plan (component + integration arc)

> **Arc.** Cross-cutting. Builds the two deferred test tiers — **component** (real-dep, Testcontainers, no cluster) and **integration** (full constellation on an olymp-owned, non-ArgoCD cluster) — on top of the existing mocked unit/component specs.
>
> **Reads with.** [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md), [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md) §4, olymp [`docs/test-harness.md`](../../../../../collite-gh/olymp/docs/test-harness.md).
>
> **Master-plan slot.** **Promoted to its own Stream T (Testing) on 2026-06-24** — was a Stream-B cross-cutting enabler. Stream T owns the component tier, the integration tier, and the **live deploy + smoke release stages**. Two faces: (1) the **component + integration tiers** (P1, P2, S3.1, S3.2) are *continuous regression gates* that **do not gate** any Spine/Body phase exit — they depend on a service having a Helm chart (D3′) + a forked stack (true since 2026-06-17) and, for the nightly, on olymp Phase A/B; (2) the **deploy/smoke stages S3.3 (Iris) + S3.4 (Sysifos)** ARE on the critical path — they cut the deploy-gated release tags for those code-complete stacks (S3.3 → master-plan **M3**; S3.4 → Sysifos arc complete). See [`../master-plan.md`](../master-plan.md) §1 (Stream T) + §4a (how T gates A and B).
>
> **Status.** Planned 2026-06-19 (Bora). **Stage 3.3 (Iris deploy + session-smoke) added 2026-06-23** — the Iris user-facing stack's live bring-up + smoke, moved here from Iris Stage 2.4 "Group B"; closes Iris Phase 2 (crosses M3). **Stage 3.4 (Sysifos deploy + workbench-smoke) added 2026-06-24** — the Sysifos back-office stack's live bring-up + layered smoke, consolidating the per-stage "deploy + smoke" T7s moved here from Sysifos Stages 1.2 / 1.3 / 2.3 / 2.5 / 2.6; closes Sysifos Phase 2.

---

## Phase summary

| Phase | Deliverable (something deployable/runnable) | Gate it adds | Depends on |
|---|---|---|---|
| **Phase 1 — Component tier** | `componentTest` source set + Gradle/`just` wiring + the first real-dep specs (Charon↔Postgres, Brontes↔MSSQL), running in `ci.yml` on every PR + merge | `just test-component` green on PR/merge | Docker-in-CI; Testcontainers (already in catalog) |
| **Phase 2 — Integration harness + first context** | The cross-repo wiring: `@RequiresContext` gate, WireMock runtime-load, olymp `infra-up/down`, the `theseus-runquery` context; nightly workflow green end-to-end | `integration-nightly.yml` green for one context | Phase 1; olymp test-harness Phase A (cluster mode + recipes) |
| **Phase 3 — Coverage rollout + hardening** | Contexts for the remaining cluster-worthy chains (golem-erp, pythia-rca, themis-routing); the **Iris deploy + session-smoke** (Stage 3.3) + the **Sysifos deploy + workbench-smoke** (Stage 3.4); image-supply optimisation; reaper; release-tag gating | nightly run-set covers the constellation; Iris + Sysifos usable on bp-dsk | Phase 2 (for the nightly contexts); each target arc has a D3′ chart |

Phases are strictly sequential **for the nightly-context stages** (3.1/3.2 need the Phase 2 harness). **Stages 3.3 (Iris) and 3.4 (Sysifos) are the exception** — each is a GitOps deploy + live smoke (mirrors the iris-bff Stage 1.4 deploy), not a `@RequiresContext` nightly spec, so they depend only on their arc's chart/image assets + a live bp-dsk (3.4 also on a live Midas-core + Excel loader), **not** on the integration harness. Olymp-side work (cluster mode, recipes, WireMock component, context registry) is tracked in olymp `docs/test-harness.md` and must reach its Phase A before kantheon Phase 2 Stage 2.3.

---

## Phase 1 — Component tier (real-dep, no cluster)

**Deliverable.** A `componentTest` source set that runs Kotest specs against **real backing dependencies in Testcontainers**, wired into `ci.yml` so it gates every PR and merge. First real-dep coverage for the two services whose correctness most depends on a real database.

### Stage 1.1 — `componentTest` source set + wiring

**Goal:** the plumbing exists and an empty `componentTest` runs green; `test-all` is provably unchanged (still mocked-only).

- T1: Add a `componentTest` source set + `componentTest` Gradle task (JVM test suite plugin) to the convention build; `@Tag("component")`; ensure `test` excludes it. *(Reference: AGENTS.md §1.4 test stack; libs catalog `testcontainers`/`kotest`.)*
- T2: `just test-component [<module>]` recipe mirroring `test-kt`/`test-all` resolution in the justfile.
- T3: A trivial `SmokeComponentSpec` standing up a `GenericContainer` (e.g. `alpine`) to prove Testcontainers boots in CI; assert container reachable.
- T4: Add the `test-component` step to `.github/workflows/ci.yml` after `test-all` (same runner; Docker available).
- T5: Assert isolation — a regression check that `just test-all` collects **zero** `@Tag("component")` specs (guards the planning-conventions §4 mocked-only stage gate).
- T6: Python mirror — `component` pytest marker + `just test-py <m> -m component`; document `testcontainers-python` usage.

**Pre-flight:** none (foundational). **DONE:** `just test-component` green in CI on a PR; `test-all` collection unchanged; `ci.yml` runs both. **Branch:** `feat/p1-s1.1-componenttest-wiring`.

### Stage 1.2 — First real-dep specs

> **Status (2026-06-20): done, scope-changed.** The Charon↔Postgres spec (T1) is **deferred** — Charon's DB extract/ingest edge isn't built yet (Charon Phase 2 / Stage 2.1; `CharonMoveExecutor` returns `NotYetImplemented` for DB pairs). Decision (Bora): ship Brontes now, land the Charon spec with Charon's DB edges. The `Containers.postgres()` factory + `SqlScripts` seed runner are in place for it. See [`tasks-p1-s1.2-realdep-specs.md`](./tasks-p1-s1.2-realdep-specs.md). The Brontes MSSQL spec is CI-only and could not be green-run locally (SQL Server segfaults under arm64 qemu — confirmed); it validates on the amd64 CI runner.

**Goal:** two services verified against real databases — the highest-value real-dep coverage.

- T1: `CharonPostgresComponentSpec` — Testcontainers Postgres; ADBC/JDBC extract→Arrow→ingest round-trip on a seeded table; assert schema fingerprint + row fidelity. *(Reference: charon/architecture.md §2 DB edges.)*
- T2: `BrontesMssqlComponentSpec` — Testcontainers MSSQL; run a Proteus-emitted SQL string against real MSSQL; assert result shape. *(Reference: deployment/local/mssql init SQL for seed.)*
- T3: A reusable `containers/` test fixture module (shared Postgres/MSSQL/Redis/WireMock `GenericContainer` factories, pinned image tags) so specs don't re-declare containers.
- T4: WireMock-as-container spec helper — boot WireMock in a container, load fixtures via the §3 admin protocol, prove the runtime-load path that integration will reuse.
- T5: Seed-data fixtures under `src/componentTest/resources/` (Northwind subset for MSSQL; a small Postgres seed).
- T6: Wire both specs into `componentTest`; confirm nightly-independent (no cluster).

**Pre-flight:** Stage 1.1 closed; Charon + Brontes build. **DONE:** both specs green in `just test-component` locally and in CI. **Branch:** `feat/p1-s1.2-realdep-specs`.

---

## Phase 2 — Integration harness + first context

**Deliverable.** The full cross-repo loop runs green for one context: `infra-up theseus-runquery` (olymp, scripted) → `:integrationTest` (kantheon, fail-fast gate + WireMock runtime-load) → `infra-down` (always). Nightly workflow exercises it.

### Stage 2.1 — `integrationTest` source set + readiness gate

**Goal:** the kantheon-side harness exists and skips cleanly with no context.

- T1: `integrationTest` source set + Gradle task accepting `-Pcontext` / `-Pnamespace`; `@Tag("integration")`; **skips** when neither prop is set. *(Reference: contracts.md §4.1.)*
- T2: `@RequiresContext(name)` Kotest extension — namespace resolution (prop or `olymp.collite/context` label), `beforeSpec` readiness assertion, **fail-fast** (no self-deploy). *(Reference: contracts.md §4.2.)*
- T3: `ContextHandle` injected into specs — resolved in-cluster service URLs + WireMock admin base.
- T4: `ContextNameRegistrySpec` (component-tier) — assert every `@RequiresContext` string resolves to a `test-contexts/<name>/` in a checked-out olymp. *(Catches drift between the repos.)*
- T5: Fabric for WireMock fixture load against an in-cluster instance (reuse the Phase 1 T4 helper, retarget base URL). *(Reference: contracts.md §3.2.)*
- T6: kubectl/k8s client dependency for the gate (read-only: get Deployment/Endpoint status); no apply/delete capability in the kantheon side.

**Pre-flight:** Phase 1 closed. **DONE:** `./gradlew :integrationTest` skips with no props; `@RequiresContext` gate unit-tested against a fake k8s API; `ContextNameRegistrySpec` green. **Branch:** `feat/p2-s2.1-integration-harness`.

### Stage 2.2 — `theseus-runquery` integration specs + fixtures

> **Status (2026-06-20): done, premise-corrected.** The chain's real shape differs from this plan's assumptions, so two tasks changed scope (full rationale in [`tasks-p2-s2.2-theseus-runquery-specs.md`](./tasks-p2-s2.2-theseus-runquery-specs.md) "Premise corrections"). In short: the tool is MCP **`query`** over StreamableHTTP (not REST `run_query`), the response is a `CallToolResult` (not `envelope/v1`), and **the `query` path makes no external HTTP calls** — so **T2 (WireMock LLM/modeler fixtures) and T5 (request-journal) are N/A**. T5's intent ("prove the chain didn't short-circuit") is met instead by asserting a real seeded MSSQL value (`t-alpha`) round-trips. The Stage 2.1 in-cluster WireMock loader stays available for a future LLM-guard scenario. Specs compile + gate now; they run green in Stage 2.3 once olymp stands the context up.

**Goal:** the end-to-end assertion code + its WireMock fixtures exist (ahead of the live cluster).

- T1: `RunQueryIntegrationSpec` `@RequiresContext("theseus-runquery")` — drive `run_query` through theseus-mcp → Proteus → Argos → Kyklop → Brontes; assert envelope result. *(Reference: deployment/local/README dependency order.)*
- T2: WireMock fixtures under `.../wiremock/theseus-runquery/` — Prometheus LLM upstream stub, Collite/modeler TTR stub. *(Reference: contracts.md §3.1.)*
- T3: OBO/bearer-roles assertion at the theseus-mcp edge (the integration-level confirmation of the fail-closed discipline mocked at unit level). *(Reference: kantheon-security.md.)*
- T4: Negative path — RLS denial surfaces correctly end-to-end (real Argos, real MSSQL).
- T5: Request-journal verification via WireMock `/__admin/requests` (asserts the LLM/modeler calls actually happened).
- T6: Mark the spec set as the `theseus-runquery` run-set member; document the context's `readiness` expectations.

**Pre-flight:** Stage 2.1; the forked `run_query` chain builds. **DONE:** specs compile + `@RequiresContext` gates them; they run (and pass) once Stage 2.3 stands the context up. **Branch:** `feat/p2-s2.2-theseus-runquery-specs`.

### Stage 2.3 — Cross-repo bring-up + nightly workflow

**Goal:** the loop is green nightly for one context. **Cross-repo: depends on olymp test-harness Phase A.**

- T1: Confirm olymp `just infra-up theseus-runquery` / `infra-down` exist + emit the namespace handshake (contracts.md §5/§6). *(Olymp deliverable; this task is the integration point + verification.)*
- T2: `.github/workflows/integration-nightly.yml` — schedule + release-tag triggers; checkout kantheon + olymp; the up→test→down loop with `trap` teardown. *(Reference: contracts.md §5.2.)*
- T3: Olymp read-credential as a CI secret; verify checkout of the private `Collite/olymp` repo in the runner.
- T4: Wire `-Pcontext`/`-Pnamespace` from `infra-up` output into the Gradle invocation.
- T5: First green nightly run end-to-end on the olymp testing server (or ephemeral k3d fallback); capture timing for the image-supply budget (Phase 3).
- T6: Failure UX — a red nightly opens a tracked issue with the failing context + logs; teardown still runs.

**Pre-flight:** Stages 2.1–2.2; **olymp test-harness Phase A live** (cluster mode + `infra-up/down` + `theseus-runquery` context + WireMock component). **DONE:** one full green nightly; a deliberately-broken service turns the nightly red without leaking a namespace. **Branch:** `feat/p2-s2.3-nightly-integration`.

---

## Phase 3 — Coverage rollout + hardening

**Deliverable.** Integration coverage across the cluster-worthy chains; the heavy job is fast and self-cleaning enough to run nightly indefinitely.

### Stage 3.1 — Additional contexts

**Goal:** the run-set covers more than one chain.

- T1: `golem-erp` context + specs — a domain Q&A turn end-to-end (Golem-ERP + its deps + Ariadne model/prompts). *(Olymp defines the context; kantheon owns specs+fixtures.)*
- T2: `themis-routing` context + specs — route classification against real Kadmos/Echo.
- T3: `pythia-rca` context + specs — a minimal investigation DAG against real Charon + Metis (gated on those arcs reaching the cluster).
- T4: Per-context WireMock fixtures + readiness definitions.
- T5: Add the new contexts to the nightly run-set; confirm parallel namespace-per-run isolation holds across concurrent contexts.
- T6: Document the "add a context" runbook (kantheon spec+fixture side; pointer to olymp's context-authoring side).
- T7: **Re-enable the deferred `theseus-runquery` result/RLS assertions** (carried over from Stage 2.3's scoped close). The first live nightly proved the harness + identity discipline, but the query *result* + RLS cases are disabled (`RunQueryIntegrationSpec.modelAlignedContext = false`) because the context's Model doesn't match its seed: Proteus' fixture model has no `dbo.sample_orders` (→ `detection_failed`), and the deployed Argos policy is `tenant_isolation` (row-level), not the column-DENY the RLS case assumed. Give the context a Model aligned with the `mssql-init` seed — **either** deploy Ariadne with a model containing `dbo.sample_orders` (+ tenant labels), **or** extend the Proteus/Argos fixture model to include it — and align the Argos policy + bearer identity (`tenant:user`) with the assertions. Then flip the flag. *(Naturally pairs with the Ariadne-bearing `golem-erp`/`themis-routing` contexts above, where a real Model is required anyway.)*

**Pre-flight:** Phase 2; each target chain builds + has a D3′ chart. **DONE:** nightly runs ≥3 contexts green, isolated; the `theseus-runquery` result/RLS assertions are re-enabled and green. **Branch:** `feat/p3-s3.1-contexts`.

### Stage 3.2 — Image supply + cluster hygiene

**Goal:** nightly is cost-bounded and self-cleaning.

- T1: Image-supply optimisation — warm local registry / image cache on the olymp testing server so nightly doesn't rebuild ~15 images cold. *(Olymp-side; kantheon verifies build-skip on unchanged SHAs.)*
- T2: Namespace reaper — label/age sweep for leaked namespaces from crashed runs (olymp CronJob; kantheon labels are the §6 handshake).
- T3: Nightly timing budget + alert threshold; split the run-set across jobs if wall-clock exceeds budget.
- T4: Release-tag gating — the full context set runs on `*/v*` tags (contracts.md §5.2), blocking a release on red.
- T5: Flake policy — quarantine tag + a tracked retry-once-then-fail for the integration tier (never retry component/unit).
- T6: Decide + document the main-branch policy (nightly-tracked vs merge-queue-gated heavy run) — the §8 judgment call, resolved with Bora.

**Pre-flight:** Stage 3.1. **DONE:** a cold nightly completes within budget; leaked namespaces are swept; release tag gates on integration. **Branch:** `feat/p3-s3.2-hardening`.

### Stage 3.3 — Iris deploy + session-smoke (bp-dsk)

> **Added 2026-06-23 (Bora).** The cluster-driven live deploy + smoke of the user-facing Iris stack (FE nginx + iris-bff), **moved here from Iris Stage 2.4 "Group B"**. It is a GitOps deploy + browser/REST smoke (mirrors iris-bff Stage 1.4), **not** a `@RequiresContext` nightly spec — so it does **not** depend on the Phase 2 integration harness, only on the Iris Stage 2.4 chart/image assets (done) + a live bp-dsk. Full task list: [`tasks-p3-s3.3-iris-deploy-smoke.md`](./tasks-p3-s3.3-iris-deploy-smoke.md).

**Goal:** the Iris stack is reachable + daily-usable on bp-dsk; the session path (create / list / switch / hydrate / reset+undo) smokes green through the FE→BFF; Iris Phase 2 closes (tags) → **crosses M3**.

- T1: Build + push `ghcr.io/boraperusic/iris:0.1.0` (`just publish-fe-image iris 0.1.0`, amd64).
- T2: Land the olymp app `clusters/bp-dsk/apps/iris/{config.json,values.yaml}` (prepared) → ApplicationSet discovers ns `iris`.
- T3: `ghcr-pull` into ns `iris` (the `clusterexternalsecret-ghcr-pull` selector edit, prepared); race-the-pod gotcha.
- T4: Keycloak `iris` SPA client in the **`kantheon`** realm (redirect URIs/web origins for `iris.192-168-1-38.nip.io`) — required (the BFF needs a bearer).
- T5: ArgoCD sync + live session-smoke (load → login → session CRUD / discovery / hydration / **reset+undo**; 401 sans bearer). Turn leg waits on a `/v2` golem behind the BFF.
- T6: Tags `iris/v0.1.0` + `iris-bff/v0.2.0`; flip olymp chartRevision → `main` on the Phase-2 merge.

**Pre-flight:** Iris Stage 2.4 Group A (chart + image recipe, done); iris-bff live on bp-dsk (Stage 1.4). **No dependency on the Phase 2 harness.** **DONE:** session-smoke green + tags landed; Iris usable. **Branch:** `feat/p3-s3.3-iris-deploy-smoke`. **Follow-on:** graduate the smoke to an automated `@RequiresContext("iris-session")` nightly spec once olymp can stand an Iris context up + a `/v2` golem exists.

### Stage 3.4 — Sysifos deploy + workbench-smoke (bp-dsk)

> **Added 2026-06-24 (Bora).** The cluster-driven live deploy + layered smoke of the back-office Sysifos stack (FE nginx + `sysifos-bff`), **consolidating the per-stage "deploy + smoke" T7s moved here from Sysifos Stages 1.2 / 1.3 / 2.3 / 2.5 / 2.6**. Like 3.3 it is a GitOps deploy + browser/REST smoke (mirrors iris-bff Stage 1.4), **not** a `@RequiresContext` nightly spec — so it does **not** depend on the Phase 2 integration harness, only on the Sysifos arc's chart/image assets (done) + a live bp-dsk **plus a reachable Midas-core + Excel loader**. Unlike Iris (Helm), Sysifos ships **Kustomize** overlays. Full task list: [`tasks-p3-s3.4-sysifos-deploy-smoke.md`](./tasks-p3-s3.4-sysifos-deploy-smoke.md).

**Goal:** the Sysifos workbench is reachable + daily-usable on bp-dsk; the layered data-entry smoke (BFF edge → shell+draft → sync CRUD → bulk grid → import → balance → reconcile → audit) is green on a fresh tenant; Sysifos Phase 2 closes (tags).

- T1: Build + push `ghcr.io/boraperusic/sysifos-bff:0.1.0` (Jib) + `ghcr.io/boraperusic/sysifos:0.1.0` (`just publish-fe-image sysifos 0.1.0`, amd64).
- T2: Land the olymp app `clusters/bp-dsk/apps/sysifos/{config.json,values.yaml}` (Kustomize; FE + BFF; BFF env → in-cluster Midas-core + loader) → ApplicationSet discovers ns `sysifos`.
- T3: `ghcr-pull` into ns `sysifos` (the `clusterexternalsecret-ghcr-pull` selector edit); race-the-pod gotcha.
- T4: Keycloak in the **`kantheon`** realm — the `sysifos` SPA client + the `sysifos-bff` service account (+ a `midas:admin` test user) for `sysifos.192-168-1-38.nip.io`.
- T5: ArgoCD sync + the layered workbench-smoke on a fresh tenant (BFF edge / shell+draft / sync CRUD + cash sub-rows / bulk grid / import both brokers / full round-trip incl. reconcile + audit).
- T6: Tags `sysifos-bff/v0.1.0` + `sysifos/v0.1.0` + the arc tag `sysifos-arc/phase-2-data-entry-v1`; flip olymp chartRevision → `main` on the Sysifos PR merge.

**Pre-flight:** Sysifos arc code-complete (all 9 stages merged; mocked specs green); Midas-core **and** the Excel loader live on bp-dsk; `sysifos-bff` Keycloak service account provisioned. **No dependency on the Phase 2 harness.** **DONE:** workbench-smoke green + tags landed; Sysifos usable. **Branch:** `feat/p3-s3.4-sysifos-deploy-smoke`. **Caveat:** the audit leg needs a Midas `GET /api/v1/audit` read endpoint (assumed in Sysifos S2.6) — defer that leg if absent. **Follow-on:** graduate to an automated `@RequiresContext("sysifos-workbench")` nightly spec once olymp can stand a Sysifos context up.

---

## Cross-repo dependency summary

| Kantheon needs | From olymp (test-harness.md phase) | By |
|---|---|---|
| `infra-up/down <context>` recipes (non-ArgoCD) | Phase A | kantheon Stage 2.3 |
| `theseus-runquery` context defined | Phase A | kantheon Stage 2.3 |
| WireMock `platform/` component (empty image, admin reachable) | Phase A | kantheon Stage 2.2/2.3 |
| Test cluster (persistent server and/or k3d mode) | Phase A | kantheon Stage 2.3 |
| namespace handshake + labels (contracts §6) | Phase A | kantheon Stage 2.1/2.3 |
| additional context definitions | Phase B | kantheon Stage 3.1 |
| warm registry + reaper | Phase B | kantheon Stage 3.2 |

---

*Doc owner: Bora. Created 2026-06-19. Per-stage task lists land at `tasks-p<phase>-s<phase.stage>-<short>.md` after this plan is ratified.*
