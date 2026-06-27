# WS-D Stage 1 — Helm library chart + migrate the 18 existing charts

> **Workstream D (Deployment), Stage 1.** Extract the shared chart shape into one Helm **library chart**, then migrate the 18 existing per-module charts onto it with **byte-equivalent** render — the regression-safe foundation before authoring the 22 new charts (D2) or deploying (D3).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0 (TDD rule), [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §1 (library-chart contract), [`master-plan.md`](./master-plan.md) §7-D5.
> **Reference chart (the shape to extract).** `services/theseus/k8s/{Chart.yaml,values.yaml,templates/{_helpers.tpl,deployment.yaml,service.yaml}}`. FE variant: `frontends/iris/k8s`. Repo: **[K]** kantheon only.

## Goal

`shared/charts/kantheon-service` (Helm `type: library`) exists; all 18 current charts depend on it and render **identically** to today (golden-file proven); `just validate-charts` gates it.

## Pre-flight

- [ ] Helm ≥ 3.8 + `helm-unittest` (or a `helm template` + diff harness) available locally + in CI.
- [ ] Branch `feat/d1-chart-library` from `main`.

## Tasks

- [ ] **T1 — Golden render snapshot (tests first).** Before touching anything, capture `helm template` output for **all 18 existing charts** (with a representative values file each) into `shared/charts/.golden/<module>.yaml`. This is the regression oracle for T4. Add a `just validate-charts` recipe that re-renders + diffs against the goldens (fails on drift).
- [ ] **T2 — Extract `shared/charts/kantheon-service` (library).** `Chart.yaml` (`type: library`, contracts §1.1) + named templates `kantheon-service.{deployment,service,helpers}` lifted verbatim from `services/theseus/k8s/templates`. Parametrise over the §1.3 values schema (replicaCount/image/imagePullSecrets/ports{http,grpc}/service/resources/telemetry/extraEnv/probes). `grpc` port optional (HTTP-only modules omit it).
- [ ] **T3 — FE + HTTPRoute named templates.** Add `kantheon-service.httproute` (gated `.Values.httpRoute.enabled`) + `kantheon-service.fe-configmap` (nginx `config.*` block) from the `frontends/iris/k8s` shape, so FE charts (iris/sysifos/landing) collapse onto the library too.
- [ ] **T4 — Migrate the 18 charts (make them thin).** For each existing chart: replace `templates/{deployment,service}.yaml` with a `templates/main.yaml` that `include`s the library templates; add the `dependencies: [kantheon-service]` `file://` ref (contracts §1.2); keep its `values.yaml` defaults. **Run `just validate-charts`** — render must be **byte-equivalent** to the T1 goldens (the gate). Fix the library, not the goldens, on any diff.
- [ ] **T5 — `helm dependency build` wiring.** Ensure the `file://` dependency resolves for all render paths: add `helm dependency build` to `just validate-charts` + document it for ArgoCD multi-source (the chart is in-repo, so the relative path resolves at render). Verify `helm template` works from a clean checkout.
- [ ] **T6 — CI gate.** Add `validate-charts` to `.github/workflows/ci.yml` (render + golden-diff for every chart on every PR). A drifted render turns CI red.

## DONE

- [ ] `shared/charts/kantheon-service` exists; 18 charts depend on it.
- [ ] `just validate-charts` green — all 18 render byte-equivalent to the pre-migration goldens (local + CI).
- [ ] Clean-checkout `helm template <any module>/k8s` works (dependency resolves).

## Follow-ups → next stage

- **D2** authors the 22 missing charts on the library (workers/agents/services/tools/infra/FEs) + publishes images.
- *(No bp-dsk leg in D1 — it's pure chart refactor; the deploy legs live in D3.)*
