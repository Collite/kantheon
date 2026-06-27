# Stage 1.3 — Pinakes stage + asset catalogue + deploy

> **Phase 1, Stage 1.3.** Branch `feat/docwh-p1-s1.3-pinakes-stage`.
>
> **Reads with.** [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`plan.md`](./plan.md) §3 Stage 1.3, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §2 (`PinakesService` RPCs `RegisterAsset`/`ListAssets`) + §11 (config), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §2 (the spine) + §7 (pipelines), [`../charon/`](../charon/) (the SeaweedFS S3 conventions Charon settled).

## Goal

`services/pinakes` skeleton with the **stage** path: raw assets land in SeaweedFS + an asset catalogue; a **mechanical** `RunPipeline` (extract→chunk→`LoadApi` only — no embed/compile yet) proves the stage→warehouse path. Both services deploy to local K3s. DONE = stage a `~/Dev/doc-store/samples/*` asset → run → keyword query; tags **`kallimachos/v0.1.0`** + **`pinakes/v0.1.0`**.

## Tasks (6)

- [ ] **T1 — `services/pinakes` skeleton; `RegisterAsset`/`ListAssets` RPCs (contracts §2).**

  Create the module (architecture §4) — `App.kt` + probes + config (contracts §11: `pinakes.grpc.port=7281`, `http.port=7280`, `seaweed.{endpoint,bucket,access-key,secret-key}`, `kallimachos.{host,port}`, `pipelines.path`). Kotlin source root `org.tatrman.pinakes.*`. Implement `RegisterAsset` (record + stage a raw asset) and `ListAssets` as real RPCs (the rest of `PinakesService` is Phase 3). `include(":services:pinakes")`.

  Acceptance: module compiles; pod starts; `RegisterAsset`/`ListAssets` answer.

- [ ] **T2 — `SeaweedAssetStore` (S3 SDK → `data-seaweedfs`) + `AssetCatalog` persistence.**

  Implement `stage/SeaweedAssetStore.kt` (AWS S3 SDK against `data-seaweedfs:8333`, bucket `docwh-stage`; reuse the infra Charon already uses, not the Charon service — architecture §2) and `catalog/AssetCatalog.kt` (asset → `asset_ref` → feed, persisted). Pinakes state schema: own small schema on the one PG (`assets`, later `pipelines`/`pipeline_runs`/`lineage`) — plan §8 leaning; confirm here.

  Acceptance: an asset stages to Seaweed (key scheme deterministic) + a catalogue row written.

- [ ] **T3 — Tests first: `SeaweedAssetStoreSpec` (mocked S3) + `AssetCatalogSpec`.**

  `SeaweedAssetStoreSpec` against a **mocked S3** (assert put + key scheme — no live Seaweed; planning-conventions §4); `AssetCatalogSpec` (asset record round-trip + feed binding).

  Acceptance: both specs green against mocks.

- [ ] **T4 — Mechanical `RunPipeline` (extract→chunk→`LoadApi` only) + `RunnerSpec`.**

  A minimal `RunPipeline` that runs **only** extract→chunk→`LoadApi` (no embed/compile/link/resolve — those are Phase 3) to prove the stage→warehouse path end-to-end. `RunnerSpec` over fakes: a staged asset flows through the mechanical stages and lands sources+parts via the Kallimachos `LoadApi`. The `KallimachosWriteClient` (Pinakes→Kallimachos internal write) is exercised here.

  Acceptance: `RunnerSpec` green; the mechanical run produces corpus entries via `LoadApi`.

- [ ] **T5 — `application.conf` + readiness (DB + extensions + Seaweed); k8s; provision DB.**

  Finalise both services' `application.conf`; readiness = DB reachable + required PG extensions present + Seaweed reachable. k8s `base/` + `overlays/local/` (`imagePullPolicy: Never`) for both. Provision the `kallimachos` database (+ the Pinakes schema) on the local Kantheon PG.

  Acceptance: `kustomize build` renders both; readiness gates on DB+extensions+Seaweed.

- [ ] **T6 — Deploy; live smoke; tag.**

  Deploy both to local K3s. **Live smoke:** stage a `~/Dev/doc-store/samples/*` asset → mechanical `RunPipeline` → `POST /query` returns the ingested content (keyword). Per planning-conventions §4 this is a deployment smoke, not an automated e2e gate. Tag **`kallimachos/v0.1.0`** + **`pinakes/v0.1.0`**; bump `gradle/libs.versions.toml`.

  Acceptance: smoke passes; both tags pushed. PR `[docwh-p1-s1.3] pinakes stage + asset catalogue + deploy`.

## DONE — Stage 1.3

- [ ] All six tasks checked.
- [ ] `services/pinakes` stages assets to Seaweed + catalogues them; `RegisterAsset`/`ListAssets` live.
- [ ] Mechanical `RunPipeline` (extract→chunk→`LoadApi`) proves the stage→warehouse path.
- [ ] Both services deploy to local K3s; live smoke (stage → run → keyword query) passes.
- [ ] Tags `kallimachos/v0.1.0` + `pinakes/v0.1.0` pushed. **Phase 1 DONE — staged ingestion + keyword warehouse live.**
- [ ] PR merged.

## Library / pattern references

- **contracts.md §2** — `PinakesService` + `Asset`. **§11** — config keys/ports.
- **architecture.md §2/§7** — the spine + the "reuse Seaweed infra, not the Charon service" rule.
- charon — the SeaweedFS S3 SDK usage + `services/` deploy pattern.
- **EXAMPLES.md §10** — Kustomize base + local overlay.

## Out of scope for Stage 1.3

- Embed/compile/link/resolve stages (Phase 2/3) — only the mechanical extract→chunk→load here.
- The full pipeline DAG / stage library / lineage RPCs (Phase 3).
- Real Seaweed / real-PG verification (integration suite).
