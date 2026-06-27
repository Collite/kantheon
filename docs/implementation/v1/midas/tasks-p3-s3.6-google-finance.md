# Stage 3.6 — Google Finance poller + FX rates

> **Phase 3, Stage 3.6.** A scheduled loader (`agents/midas/loaders/google-finance`) refreshes FX rates and market prices daily on a Quartz schedule, writing through Midas-core; stale-data warnings surface in envelopes.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §Stage 3.6, [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §4.2 (loader API) + §2.7 (FX-rate write endpoint).
> **Templates/examples.** Excel loader (the lifecycle/deploy precedent): `agents/midas/loaders/excel`. Ktor: EXAMPLES.md §1a. Kotest+Wiremock: EXAMPLES.md §9. Quartz + google-sheets-api added to the catalog in T0.

## Goal

Two Quartz jobs (FX rates @ 23:00 UTC, market prices @ 23:30 UTC) fetch from Google Finance (via a Sheet of `GOOGLEFINANCE` formulas) and upsert through Midas-core; the manual `POST /runs:trigger` works; stale FX (>24h) surfaces a `WARN` `ResponseMessage` that Iris renders as a "stale" badge.

## Pre-flight

- [ ] Midas-core FX-rate + price write endpoints live (S1.3/S1.4 ✓; confirm `POST /fx-rates` per contracts §2.7).
- [ ] A Google service account + a fixture Google Sheet of `GOOGLEFINANCE` formulas (Bora-owned; Sheets API quota note in plan §6).
- [ ] Decide the price store: v1 picks a separate `asset_prices` table (plan §Stage 3.6 T4) — add the migration if not present.
- [ ] Branch `feat/p3-s3.6-google-finance` from `main`.

## Tasks

- [ ] **T0 — Catalog deps + module wiring.** Add `quartz` and the `google-sheets-api` Java client to `gradle/libs.versions.toml` (neither present yet — S1.1 T2 deferred Quartz). Wire into `agents/midas/loaders/google-finance/build.gradle.kts`; module skeleton + `App.kt` (EXAMPLES.md §1a) if not already stubbed from S1.1.
- [ ] **T1 — Sheets client tests first.** Wiremock-driven spec against a fixture Sheet response containing `GOOGLEFINANCE` outputs; assert the parser extracts `{pair/asset, rate/price, as_of_date}`. Define before impl.
- [ ] **T2 — `SheetsClient` impl.** `parser/SheetsClient.kt` using the google-sheets-api client + service-account auth; batch all pairs into one sheet read (quota mitigation, plan §6). Make T1 pass.
- [ ] **T3 — FX-rate poller (tests first + impl).** Quartz job @ 23:00 UTC: read active currency pairs (from `portfolios.base_currency` + asset currencies in the DB), fetch rates, upsert via Midas-core `POST /fx-rates`. Tests: mocked Midas-core (Wiremock) — job computes the right pair set and issues the upserts; idempotent re-run.
- [ ] **T4 — Market-price poller (tests first + impl) + `asset_prices` migration.** Quartz job @ 23:30 UTC: fetch active assets' close prices, write to the `asset_prices` table feeding `mv_portfolio_value_daily` (Flyway migration for the table if needed). Tests: job writes the expected rows; mocked DB/Midas-core.
- [ ] **T5 — Manual trigger + run history.** `POST /runs:trigger` (`{kind: "fx_rates"|"market_prices", date?}`) + `GET /runs` + `GET /runs/{id}` per contracts §4.2; reuse the Excel-loader `loader_runs` pattern (in-memory store acceptable in v1 per S1.5 precedent; note the DB-backed swap as a follow-up).
- [ ] **T6 — Stale-data warning surfacing (tests first + impl).** If an FX rate read by a Midas-core MCP tool is older than 24h, the tool attaches a `ResponseMessage{severity=WARN}` (Rule 6, EXAMPLES.md §3a). Spec: a stale rate produces the WARN; a fresh one does not. (Iris renders the "stale" badge — that FE bit is Iris-side; here we guarantee the signal.)

## DONE (plan §Stage 3.6)

- [ ] Both pollers run on schedule; manual `:trigger` works; run history queryable.
- [ ] FX + price upserts are idempotent and write through Midas-core / `asset_prices`.
- [ ] Stale-FX (>24h) surfaces a WARN `ResponseMessage` (T6).
- [ ] (Demo) 7-day unattended run refreshes FX/prices without incident; positions value at current market prices.

## Follow-ups (not in this stage)

- Yahoo Finance / SFTP / REST adapter loaders — out of v1 (plan §7).
- DB-backed `loader_runs` store (shared with the Excel loader follow-up).
- S3 lifecycle for any cached sheet exports — v1.x.
