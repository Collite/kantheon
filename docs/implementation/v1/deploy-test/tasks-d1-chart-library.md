# WS-D Stage 1 — Helm library chart + migrate the 18 existing charts

> **Workstream D (Deployment), Stage 1.** Extract the shared chart shape into one Helm **library chart**, then migrate the 18 existing per-module charts onto it with **byte-equivalent** render — the regression-safe foundation before authoring the 22 new charts (D2) or deploying (D3).
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0 (TDD rule), [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §1 (library-chart contract), [`master-plan.md`](./master-plan.md) §7-D5.
> **Reference chart (the shape to extract).** `services/theseus/k8s/{Chart.yaml,values.yaml,templates/{_helpers.tpl,deployment.yaml,service.yaml}}`. FE variant: `frontends/iris/k8s`. Repo: **[K]** kantheon only.

## Goal

`shared/charts/kantheon-service` (Helm `type: library`) exists; all 18 current charts depend on it and render **identically** to today (golden-file proven); `just validate-charts` gates it.

## Pre-flight

- [x] Helm ≥ 3.8 + a `helm template` + diff harness available locally + in CI. *(Helm v4 local, `azure/setup-helm@v3.16.2` in CI; harness = `shared/charts/validate.sh`, no `helm-unittest` needed.)*
- [x] Branch `feat/d1-chart-library` from `main`.

## Tasks

- [x] **T1 — Golden render snapshot (tests first).** `shared/charts/validate.sh {capture|check}` renders every `<module>/k8s` chart (default values) and diffs against `shared/charts/.golden/<module>.yaml`; goldens captured from the **pre-migration** charts. `just validate-charts` wraps it.
- [x] **T2 — Extract `shared/charts/kantheon-service` (library).** `Chart.yaml` (`type: library`) + `kantheon-service.{deployment,service,helpers}`. `grpc` port + service port gated on `.Values.ports.grpc` (HTTP-only modules omit). **Deviation from the idealized §1.3 env schema:** the container `env:` block diverges genuinely per module (var names, OTel shape, DB/secret env) — far beyond `extraEnv`, so it is delegated to a per-module `define "<Chart.Name>.env"` hook (`templates/_env.tpl`) the library includes at `nindent 12`. Optional `kantheon-service.{volumeMounts,volumes}` hooks (empty default, module-overridable) cover golem's Shem mount.
- [x] **T3 — FE + HTTPRoute named templates.** `kantheon-service.{fe-deployment,fe-service,fe-configmap,httproute}` from `frontends/iris/k8s`. The `checksum/config` is computed as `sha256sum` of `fe-configmap` + a trailing `\n` (a library template can't use `$.Template.BasePath`), reproducing the pre-library hash exactly.
- [x] **T4 — Migrate the 18 charts (make them thin).** Each keeps its `templates/{deployment,service}.yaml` **filenames** (one-line `include`s) rather than a single `main.yaml` — this preserves the `# Source:` provenance lines, so the render stays **byte-equivalent** (a `main.yaml` would have renamed both Sources and failed the gate). Added the `dependencies: [kantheon-service]` `file://` ref; dropped each `_helpers.tpl`. `just validate-charts` green for all 18. Conditional branches (db/telemetry/secretEnv/auth/otlpHost/shem) verified byte-equivalent old-vs-new with flags set, beyond the default-values golden.
- [x] **T5 — `helm dependency build` wiring.** `validate.sh` runs `helm dependency build` before render; verified from a clean checkout (vendored `charts/` + `Chart.lock` removed → rebuilt + green). Vendored artifacts gitignored (`**/k8s/charts/`, `**/k8s/Chart.lock`). ArgoCD multi-source documented in `shared/charts/kantheon-service/README.md`.
- [x] **T6 — CI gate.** `helm-charts` job in `.github/workflows/ci.yml` (Helm + just, no JVM) runs `just validate-charts` on every PR/merge.

## DONE

- [x] `shared/charts/kantheon-service` exists; 18 charts depend on it.
- [x] `just validate-charts` green — all 18 render byte-equivalent to the pre-migration goldens (local + CI).
- [x] Clean-checkout `helm template <any module>/k8s` works (dependency resolves via `helm dependency build`).

## Follow-ups → next stage

- **D2** authors the 22 missing charts on the library (workers/agents/services/tools/infra/FEs) + publishes images.
- *(No bp-dsk leg in D1 — it's pure chart refactor; the deploy legs live in D3.)*
