# Midas — Phase 1 Stage 1.5 task list

**Excel loader (upload → preview → commit).**

Companions: [`plan.md`](./plan.md) §Stage 1.5, [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §4.1 (loader REST + `LoaderRun`/`LoaderPreview`).

> **Status: T1–T7 DONE (2026-06-21).** Parser + mapper + the full upload→preview→commit lifecycle + deploy wiring are implemented and tested. The *live cluster* smoke (`k8s/smoke.sh`) needs your K3s + a deployed midas-core/loader — the manifests are validated (`kubectl kustomize`) and the script is syntax-checked, but I can't run a cluster here. Fixtures are **synthetic** (POI-generated) until real broker exports are in hand — swap them in and re-run `genFixtures`.
>
> **One scoping decision:** `loader_runs` is an **in-memory** `LoaderRunStore` for v1 (interface-backed). The DB-backed implementation over the `loader_runs` table (V0001, RLS) is a clean swap behind the interface — deferred because it needs the loader's own tenant-pinned DB connection. In-memory is correct for a single local replica; **persist before multi-replica / restart-durability matters.**

## Tasks

- [x] **T1 — Fixtures.** `test/fixtures/BrokerFixtures.kt` builds two synthetic statements (POI) matching the `alpha`/`beta` templates; committed `.xlsx` at `src/test/resources/fixtures/{alpha,beta}_sample.xlsx` via the reproducible `genFixtures` Gradle task. Data lives in code (reviewable), not opaque binaries.
- [x] **T2 — POI parser + broker registry.** `parser/BrokerRegistry.kt` (loads `brokers/*.yaml` → `BrokerTemplate`); `parser/ExcelParser.kt` (POI `XSSFWorkbook`: named sheet, configurable header row, header→column resolution, `RawRow` per data row, numeric/date normalisation). Two templates: `brokers/alpha.yaml`, `brokers/beta.yaml` (different sheet/header-row/date-format/kind vocabulary). Tests: `BrokerRegistrySpec`, `ExcelParserSpec` (incl. parsing the committed files from the classpath).
- [x] **T3 + T4 — mapper.** `mapper/TransactionMapper.kt` — `RawRow` → `midas.v1.Transaction` draft: kind vocabulary → `TransactionKind`, ISO + day-first date parsing, decimals, currency, stable `external_id` (`<broker>:<ref>`), `source = TX_SRC_LOADER_EXCEL`; per-row errors (unknown kind / bad date) become `DraftRow.error` (→ `PV_ERROR`) rather than throwing. The draft's `asset_id` is **not** set here — `symbol` travels on `DraftRow` for resolution against Midas-core at preview/commit. Test: `TransactionMapperSpec`.
- [x] **T5 — Loader lifecycle tests.** `LoaderServiceSpec` (fakes instead of Wiremock — cleaner, same coverage): upload→preview→commit, idempotent re-upload (same run), `skip_existing` re-commit (inserts nothing), parse-failure → `LR_FAILED`, unknown-broker → 400, dup detection. `LoaderRoutesSpec` drives the real HTTP surface (multipart upload → preview → commit) via `testApplication` + a recording client; 401 without bearer.
- [x] **T6 — Loader lifecycle implementation.** Ktor routes under `/api/v1` (contracts §4.1: `/uploads`, `/runs`, `/runs/{id}`, `/runs/{id}/preview`, `/runs/{id}/commit`); `LoaderService` orchestration; `LoaderRunStore` (in-memory v1) + `BlobStore` (FS; re-parsed source of truth, content-hash keyed for idempotency); `HttpMidasCoreClient` (proto-JSON, OBO bearer + `X-Tenant-Id`) — symbol→asset resolve-or-create + commit via `POST /transactions:batch`; preview dup detection by listing the portfolio's existing `external_id`s; validate-only `BearerAuthenticator`.
- [x] **T7 — Deploy + smoke + cleanup.** Kustomize base+local overlay completed (blob `emptyDir` volume + `EXCEL_LOADER_BLOB_DIR`; `imagePullPolicy: Never` local) — both overlays validated with `kubectl kustomize`. Blob cleanup is an in-process `BlobJanitor` (the v1 "cron": prune `upload_blob_ref`s older than 24h, hourly; configurable; `BlobJanitorSpec`) — no separate CronJob/shared-volume. End-to-end smoke at [`k8s/smoke.sh`](../../../../agents/midas/loaders/excel/k8s/smoke.sh) (create client+portfolio → upload → preview → commit → verify transactions → re-run idempotent). **Also fixed an S1.4 gap:** midas-core's Deployment/Service now expose the MCP port `7311`.

## DONE criteria (plan §Stage 1.5)

- [x] `just deploy-kt … && upload fixture → preview → commit` succeeds — manifests build + the flow is scripted (`k8s/smoke.sh`); the lifecycle is proven by `LoaderRoutesSpec`. *(One live cluster run on bp-dsk still wanted to tick the operational box.)*
- [x] One fixture broker template parses cleanly (both alpha + beta — `ExcelParserSpec`).
- [x] Idempotent re-run skips zero new transactions (`LoaderServiceSpec`: re-upload → same run; `skip_existing` re-commit → `inserted=0`).

## Notes / decisions

- **Asset resolution deferred to the lifecycle.** `Transaction` has no `symbol`; the mapper emits `asset_id`-less drafts + the broker `symbol`. T6 resolves/creates the asset via Midas-core before commit (RLS + the assets `WITH CHECK` keep it tenant-scoped).
- **Fixtures are synthetic.** Replace `BrokerFixtures` row data + re-run `genFixtures` with real broker exports; the templates (`brokers/*.yaml`) may need column/vocabulary tweaks per real file.
