# WS-D Stage 2 — Author the 22 missing charts + publish images

> **Workstream D (Deployment), Stage 2.** On the library chart from D1, author a thin chart for every chartless deployable and publish its image to GHCR. After this, every v1 module is renderable + has an image — ready for D3 to wire into bp-dsk.
>
> **Reads with.** [`tasks-overview.md`](./tasks-overview.md) §0, [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) §1 (chart) + §8 (images) + §2.5 (deploy descriptor), [`tasks-d1-chart-library.md`](./tasks-d1-chart-library.md).
> **Reference.** A thin migrated chart from D1 (e.g. `services/theseus/k8s`); FE: `frontends/iris/k8s`. Repo: **[K]** kantheon.
> **The 22 chartless deployables** (grouped by variant): **Kotlin services** `services/{charon,kallimachos,pinakes,report-renderer}`; **Kotlin agents** `agents/{hebe,kleio,midas/core,midas/loaders/excel,pythia,sysifos-bff}`; **MCP wrappers** `tools/{ariadne-mcp,charon-mcp,echo-mcp,kadmos-mcp,kallimachos-mcp,metis-mcp}`; **Python** `services/metis`, `workers/steropes`; **FE nginx** `frontends/{landing,sysifos,kallimachos-browse}`; **infra** `infra/backstage`.

## Goal

All 22 modules have a `<module>/k8s` chart depending on `kantheon-service`, each rendering valid manifests (golden-tested), and a published GHCR image; `just validate-charts` green for the whole estate.

## Pre-flight

- [x] D1 DONE (library chart + 18 charts migrated + `just validate-charts` green).
- [x] Each module builds + has a working image recipe (`publish-image` / `publish-fe-image` / `build-py`). *(build-py caveat: host-arch only, no GHCR tag — see the T6 runbook.)*
- [x] Branch `feat/d2-charts-images` (stacked on `feat/d1-chart-library`).

## Tasks (per group: write a render-golden test first, then the chart, then publish)

- [x] **T1 — Kotlin services + agents (10 charts).** Authored on the library. **Deviation from wording:** thin charts keep `templates/{deployment,service}.yaml` (one-line includes) + a per-module `templates/_env.tpl` hook, **not** `main.yaml` (same rationale as D1 — provenance + the per-module env surface). charon = `envFrom` + `_volumes.tpl` (connections mount) + `extraEnv` storage wiring; midas-core = `db.*` + `MIDAS_DB_PASSWORD` secretKeyRef; pythia = `envFrom`; hebe/excel = `_volumes.tpl`. Deploy descriptors in each `<module>/k8s/README.md`. Golden per chart via `just validate-charts`.
- [x] **T2 — MCP wrapper charts (6).** HTTP-only, mirrored `tools/theseus-mcp/k8s`; port env → `ports.http`, OTel normalized (theseus idiom), downstream → `extraEnv`.
- [x] **T3 — Python charts (2).** `metis` (kadmos OTLP-host idiom), `steropes` (theseus idiom) — chart identical to backend; image via `build-py` (noted in descriptor).
- [x] **T4 — FE nginx charts (3).** `landing` (port 80, no BFF — dispatch by hostname; config via `config.extra`), `sysifos` (port 7602 → `sysifos-bff:7601`), `kallimachos-browse` (**best-effort** — port/`/healthz` placeholders, proxies `/library` not `/bff`). httpRoute renders both enabled/disabled.
- [x] **T5 — `infra/backstage` chart (best-effort).** Chose the **backend** templates (Backstage reads env, not a generated `env.js`) + `httproute.yaml`; port 7007, `/healthcheck`, DB/session/Keycloak secrets via `secretKeyRef optional`.
- [~] **T6 — Publish images to GHCR — HANDED OFF (needs Bora's `write:packages` PAT).** Exact per-module commands + caveats (Python amd64, `midas-excel-loader` basename, backstage custom build) in [`d2-image-publish.md`](./d2-image-publish.md). Not run by the agent (no creds; outward push).
- [x] **T7 — Estate-wide `validate-charts` + descriptor index.** `just validate-charts` green for **all 40 charts**; goldens re-keyed by chart `name:` (fixes the `core`/`excel` dir-basename collision). Index at [`../../../architecture/deploy-test/deploy-descriptors.md`](../../../architecture/deploy-test/deploy-descriptors.md).

## DONE

- [x] All 22 new charts render valid manifests; `just validate-charts` green across the estate (40 charts, local + CI); `helm lint` clean (0 warnings) for all 40.
- [~] Every module has a pulled-verified GHCR image — **pending Bora** (T6 runbook; credential-gated).
- [x] Deploy-descriptor index complete (feeds D3).

## Follow-ups → next stage

- **D3** wires each module into bp-dsk (ArgoCD apps + platform deps, waves 1–7) using the descriptor index.
- *(No bp-dsk run leg here — D2 is chart+image authoring; the live deploy + smoke is D3, reusing the testing S3.3/S3.4 pattern.)*
