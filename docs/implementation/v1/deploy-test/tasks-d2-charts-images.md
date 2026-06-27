# WS-D Stage 2 — Author the 22 missing charts + publish images

> **Workstream D (Deployment), Stage 2.** On the library chart from D1, author a thin chart for every chartless deployable and publish its image to GHCR. After this, every v1 module is renderable + has an image — ready for D3 to wire into bp-dsk.
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §1 (chart) + §8 (images) + §2.5 (deploy descriptor), [`tasks-d1-chart-library.md`](./tasks-d1-chart-library.md).
> **Reference.** A thin migrated chart from D1 (e.g. `services/theseus/k8s`); FE: `frontends/iris/k8s`. Repo: **[K]** kantheon.
> **The 22 chartless deployables** (grouped by variant): **Kotlin services** `services/{charon,kallimachos,pinakes,report-renderer}`; **Kotlin agents** `agents/{hebe,kleio,midas/core,midas/loaders/excel,pythia,sysifos-bff}`; **MCP wrappers** `tools/{ariadne-mcp,charon-mcp,echo-mcp,kadmos-mcp,kallimachos-mcp,metis-mcp}`; **Python** `services/metis`, `workers/steropes`; **FE nginx** `frontends/{landing,sysifos,kallimachos-browse}`; **infra** `infra/backstage`.

## Goal

All 22 modules have a `<module>/k8s` chart depending on `kantheon-service`, each rendering valid manifests (golden-tested), and a published GHCR image; `just validate-charts` green for the whole estate.

## Pre-flight

- [ ] D1 DONE (library chart + 18 charts migrated + `just validate-charts` green).
- [ ] Each module builds + has a working image recipe (`publish-image` / `publish-fe-image` / `build-py`).
- [ ] Branch `feat/d2-charts-images` (or per-group branches).

## Tasks (per group: write a render-golden test first, then the chart, then publish)

- [ ] **T1 — Kotlin services + agents (10 charts).** `services/{charon,kallimachos,pinakes,report-renderer}` + `agents/{hebe,kleio,midas/core,midas/loaders/excel,pythia,sysifos-bff}`. Each: thin `k8s/{Chart.yaml(dep kantheon-service),values.yaml,templates/main.yaml}` with its `ports.{http,grpc}`, resources (architecture §13), `extraEnv` downstream defaults, and a **deploy descriptor** (contracts §2.5: pg-database/seaweed/keycloak/wave). **Tests first:** a `helm template` golden per chart in `just validate-charts`.
- [ ] **T2 — MCP wrapper charts (6).** `tools/{ariadne-mcp,charon-mcp,echo-mcp,kadmos-mcp,kallimachos-mcp,metis-mcp}` — HTTP-only (StreamableHTTP `/mcp`), mirror `tools/theseus-mcp/k8s` (which exists). `ports.http` only; `extraEnv` → the backing service. Golden tests first.
- [ ] **T3 — Python charts (2).** `services/metis`, `workers/steropes` — same chart shape (the Deployment/Service are language-agnostic); image via `build-py` (not Jib). Note the `image.repository` + pull policy. Golden tests first.
- [ ] **T4 — FE nginx charts (3).** `frontends/{landing,sysifos,kallimachos-browse}` — mirror `frontends/iris/k8s`: `config.*` (BFF upstream / Keycloak where applicable), `httpRoute.enabled: true` + `hostname`. `landing` is in scope (must work); `sysifos` pairs with testing S3.4; `kallimachos-browse` best-effort. Golden tests first.
- [ ] **T5 — `infra/backstage` chart (best-effort).** Backstage/Node image; HTTP + HTTPRoute. Best-effort per §7-D3 — author the chart but don't gate the estate on it.
- [ ] **T6 — Publish images to GHCR.** Run the right recipe per module (`publish-image` Jib multi-arch / `publish-fe-image` amd64 / `build-py`+push), tag `:testing` for integration + a release tag for bp-dsk live (contracts §8). Verify each pulls.
- [ ] **T7 — Estate-wide `validate-charts` + descriptor index.** `just validate-charts` green for **all 40 charts**; compile the per-module deploy descriptors into a single index (`docs/architecture/deploy-test/deploy-descriptors.md`) that D3 consumes for the olymp app PRs.

## DONE

- [ ] All 22 new charts render valid manifests; `just validate-charts` green across the estate (local + CI).
- [ ] Every module has a pulled-verified GHCR image (`:testing` + release tag).
- [ ] Deploy-descriptor index complete (feeds D3).

## Follow-ups → next stage

- **D3** wires each module into bp-dsk (ArgoCD apps + platform deps, waves 1–7) using the descriptor index.
- *(No bp-dsk run leg here — D2 is chart+image authoring; the live deploy + smoke is D3, reusing the testing S3.3/S3.4 pattern.)*
