# Phase 1 — Warehouse core + stage

> **Reads with.** [`plan.md`](./plan.md) §3 (Phase 1), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §1–§4 + §6 (wiki model) + §13 (testing), [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §1–§3, [`../../planning-conventions.md`](../../planning-conventions.md) §4 (mocked-unit testing policy). Seed: `~/Dev/doc-store`. Service template: [`../charon/`](../charon/) (the `services/` + Seaweed conventions).

## Phase deliverable (deployable)

`services/kallimachos` (Ktor + Exposed) on **one Postgres**: sources / parts / notebooks(marts); **one-transaction** mechanical ingestion (parsers ported from doc-store: txt/md/html/pdf → `DocNode` → parts); keyword `query` + `getById`. `services/pinakes` skeleton: raw assets stage to SeaweedFS + an asset catalogue. Deployed to local K3s. Tags **`kallimachos/v0.1.0`** + **`pinakes/v0.1.0`**.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **1.1** — protos + Kallimachos skeleton + ported domain | Module compiles; per-format parser suites green; pod starts (routes stubbed) | [`tasks-p1-s1.1-skeleton.md`](./tasks-p1-s1.1-skeleton.md) |
| **1.2** — relational + full-text planes + one-tx ingestion + marts | Ingest → keyword query round-trips on `Port` fakes; one-tx rollback proven | [`tasks-p1-s1.2-ingestion-marts.md`](./tasks-p1-s1.2-ingestion-marts.md) |
| **1.3** — Pinakes stage + asset catalogue + deploy | Stage a `~/Dev/doc-store/samples/*` asset → mechanical run → keyword query; tags `kallimachos/v0.1.0` + `pinakes/v0.1.0` | [`tasks-p1-s1.3-pinakes-stage.md`](./tasks-p1-s1.3-pinakes-stage.md) |

## Sequencing

Strictly sequential (the whole arc is sequential at phase granularity).

```
Stage 1.1 ──► 1.2 ──► 1.3
 protos+skeleton  ingestion+marts  pinakes stage + deploy + tags
```

## Pre-flight for the phase (plan §2)

- [ ] `pinakes`/`kallimachos`/`kleio` package conventions locked (contracts §1/§2/§8). Service protos are platform-service roots `org.tatrman.pinakes.v1` / `org.tatrman.kallimachos.v1`; the agent proto is the constellation root `org.tatrman.kantheon.kleio.v1` (lands P5). **Kotlin source roots** mirror charon: `org.tatrman.kallimachos.*` / `org.tatrman.pinakes.*` (platform-service convention — these are *not* `org.tatrman.kantheon.*`).
- [ ] Kantheon PG carries `vector` + `age` extensions (confirmed available, Bora 2026-06-20) — needed live from P2; P1 uses fakes + the relational/fulltext planes.
- [ ] SeaweedFS S3 gateway reachable (`data-seaweedfs:8333`) — Charon already uses it; raw blobs go direct via the S3 SDK (reuse the infra, not the Charon service).
- [ ] doc-store source to port present (`adapters/`, `ingestion/`, parsers) at `~/Dev/doc-store`.
- [ ] Exposed / pgvector-jdbc / AGE-jdbc / parsers (jsoup, flexmark, PDFBox) / Koog versions pinned — Stage 1.1 T1.

## Testing policy (every stage)

Mocked unit/component only (architecture §13, planning-conventions §4): Kotest StringSpec + MockK + in-memory `Port` fakes + Wiremock for Prometheus. The doc-store test corpus is the **parity oracle** for the ported parsers. The integration suite (real pgvector/AGE, live Prometheus, OBO/Argos RLS, in-K3s e2e) is separate and does **not** gate stage DONE.

## Aggregate progress (plan §11)

- [ ] **1.1** protos + Kallimachos skeleton + ported parsers.
- [ ] **1.2** relational+fulltext planes + one-tx ingestion + marts + keyword query.
- [ ] **1.3** Pinakes stage + asset catalogue + deploy. **P1 — `kallimachos/v0.1.0` + `pinakes/v0.1.0`.**

When all three are checked, push both tags and move to Phase 2.

## Up / across

- Up: [`./README.md`](./README.md). Neighbours: [`tasks-p2-overview.md`](./tasks-p2-overview.md) … [`tasks-p5-overview.md`](./tasks-p5-overview.md).
