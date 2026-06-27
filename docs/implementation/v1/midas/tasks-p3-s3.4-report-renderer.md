# Stage 3.4 — Report-renderer service + v1 templates

> **Phase 3, Stage 3.4.** Stand up `services/report-renderer` (skeleton landed empty in S1.1) and render the three v1 templates in all four output formats (XLSX / PPTX native, PDF / HTML via headless Chromium).
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §Stage 3.4, [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §10 (rendering pipeline), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §8 (API) + §1.3 (`report.proto`) + §9.3 (templates).
> **Templates/examples.** Ktor service bootstrap: EXAMPLES.md §1a. Kustomize: EXAMPLES.md §10. Recent service to copy module shape from: `services/charon`. POI + Playwright are added to the catalog in T0.

## Goal

`services/report-renderer` healthy in K3s; `GET /templates`, `GET /templates/{id}`, `POST /render`, `GET/DELETE /artifacts/{id}` answer per contracts §8; the three v1 templates render in XLSX, PPTX, PDF, and HTML against fixture data.

## Pre-flight

- [ ] `report/v1` proto compiled (S1.2 ✓).
- [ ] S3.3 DONE — the PPTX/performance templates use real TWR/MWR numbers (mock the calc `DataFetcher` until then if parallelising).
- [ ] Three template files authored (T7 — Bora-owned content; can be stubbed minimally to unblock engine work).
- [ ] Branch `feat/p3-s3.4-report-renderer` from `main`.

## Tasks

- [ ] **T0 — Catalog deps.** Add `playwright`/`playwright-kotlin` to `gradle/libs.versions.toml` (not present yet — S1.1 T2 deferred it). Confirm `apache-poi-ooxml` is present (it is). Wire both into `services/report-renderer/build.gradle.kts`.
- [ ] **T1 — Service skeleton + template-resolver tests first.** Wiremock/TestApplication spec (EXAMPLES.md §9): `GET /templates` returns the three v1 `ReportTemplate`s; `GET /templates/{id}` resolves + reads bytes from classpath (`templates/{id-with-dots→slashes}.{ext}`, architecture §10.1). Define before impl.
- [ ] **T2 — `TemplateResolver` + `App.kt`.** Ktor bootstrap (`App.kt` ≤45 lines, EXAMPLES.md §1a) + `RepoBundledResolver` reading `src/main/resources/templates/`; `TemplateResolver` interface so the v1.x `S3Resolver` swaps in without API change. `ParamValidator.validate(args_json, Template.params)` per `ParamDef`/`ParamKind` (contracts §1.3). Make T1 pass.
- [ ] **T3 — XLSX engine tests first.** Render `portfolio-statement:v1` against fixture data; assert named ranges (`{{portfolio.name}}`, `{{portfolio.base_currency}}`, `{{as_of_date}}`) are populated and the table region `tbl_positions` has the expected row count (contracts §9.3).
- [ ] **T4 — XLSX engine impl.** POI `XSSFWorkbook` named-range substitution + table-region row insertion with style preservation (architecture §10.2). `DataFetcher` calls Midas-core MCP tools (`midas.position.valuation:v1` etc.) for report data; mock it in unit tests. Make T3 pass.
- [ ] **T5 — PPTX engine + PDF/HTML pipeline tests first + impl.** PPTX via POI slide-and-shape API (`performance-report.v1.pptx` — cover/summary/breakdown/transactions slides). PDF/HTML: render data → print-CSS HTML → Playwright headless-Chromium "print to PDF" (architecture §10.2). Smoke assertions: output byte-size > 5 KB, correct content-type per `OutputFormat`.
- [ ] **T6 — `/render` route + artifact store + deploy.** Handler dispatches by `template_id` + `OutputFormat`; `ArtifactStore` writes to `/var/midas/artifacts/` (FS in v1) keyed by `artifact_id`; `RenderReportResponse` returns `artifact_url` + `mime_type` + `size_bytes` + `expires_at`; cleanup cron after 7 days. `GET /artifacts/{id}` streams; `DELETE` removes. Jib build + Kustomize apply (EXAMPLES.md §10); `/health`,`/ready` 200. `POST /render` synchronous, ≤30s (contracts §8).
- [ ] **T7 — Three v1 templates as fixtures (Bora-owned content).** Author `portfolio-statement.v1.xlsx`, `performance-report.v1.xlsx`, `performance-report.v1.pptx`, `transaction-ledger.v1.xlsx` in Excel/PPT; place under `src/main/resources/templates/`. Render each + open manually to verify fidelity (POI PPTX fidelity is a known risk — keep v1 layouts simple per plan §6).

## DONE (plan §Stage 3.4)

- [ ] All three templates render in all four formats (XLSX/PPTX/PDF/HTML); engine specs green.
- [ ] `report-renderer` healthy in K3s; `POST /render` returns a downloadable artifact synchronously (≤30s).
- [ ] Artifact retention cron deletes after 7 days.

## Follow-ups (not in this stage)

- S3-backed template + artifact storage; signed download tokens — v1.x.
- Job+poll async render if 30s proves insufficient — v1.x.
- User-editable templates — out of v1 scope (plan §7).
