# Stage 3.2 — Q&A green path (needs Arges PG worker)

> **Phase 3, Stage 3.2.** The full investment Q&A turn goes green: Iris → Themis → Golem-Investment → (theseus-mcp + Arges for data, Midas-core MCP for calc) → `ConversationalResponse` of `envelope/v1` blocks.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md) §0 (reconciliations), [`plan.md`](./plan.md) §Stage 3.2, [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §6.3 (read path), [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §3 (MCP tools).
> **Templates.** Golem graph: `agents/golem/src/main/kotlin/.../GolemGraph*` (the existing template, parametrised by Shem). Query path: `tools/theseus-mcp` (tool `query`/`compile`). PG worker: `workers/arges`. MCP calc: `agents/midas/core/src/main/kotlin/.../mcp/MidasTools.kt`. Envelope assembly: EXAMPLES.md §6. Kotest+Wiremock: EXAMPLES.md §9.

## Goal

Five canned questions each produce the correct `envelope/v1` Block sequence at the **mocked-component level**. Real TWR + FIFO land in Midas-core's `calc/` (replacing the S1.4 stubs). Live end-to-end through a running Iris is deferred to Stream T (confirmed in the Phase-3 demo).

## Pre-flight

- [ ] S3.1 DONE (Shem assembled + routed).
- [ ] **Arges `arges/v0.1.0` cut** + **Proteus PostgreSQL unparse green** for the five `q.midas.*` queries (Arges S1.2; overview §0/§4).
- [ ] **`midas_app_readonly`** role migration applied to the `midas` database (arges contracts §6) — the live read path role.
- [ ] theseus-mcp reachable from the kantheon namespace; Kyklop routes the `pg-midas` profile to Arges.
- [ ] Branch `feat/p3-s3.2-qa-green-path` from `main`.

## Tasks

- [ ] **T1 — Query-path smoke (manual + recorded fixture).** Exercise `theseus-mcp.query`/`compile` for `q.midas.positions_current` against the `pg-midas` profile → Arrow IPC rows from `mv_position_current`. Capture the response as a Wiremock fixture for the component tests (this is the seam S3.2 mocks; the live call is a demo confirmation, not the gate).
- [ ] **T2 — Golem-Investment graph wiring tests first.** Wiremock-driven Kotest spec (EXAMPLES.md §9): "current AAPL position in Smith portfolio" → expected `Block` sequence; theseus-mcp + Midas-core MCP edges stubbed. Define the expected envelope (table + narrative) before wiring.
- [ ] **T3 — Golem-Investment graph wiring impl.** Parametrise the existing Golem template graph (Koog `AIAgentStrategy`; EXAMPLES.md §5) with the `investment` Shem from S3.1. Resolve `preferred_capabilities`/`preferred_queries` to live tool/query endpoints; data reads go via **theseus-mcp `query`** (overview §0), calc via Midas-core MCP. Make T2 pass.
- [ ] **T4 — MCP tool calls (happy path) tests first + wiring.** Golem-Investment calls `midas.position.valuation:v1` for valuation questions and `midas.portfolio.performance:v1` for return questions (contracts §3.1/§3.2 input shapes); results render via `envelope/v1` blocks (EXAMPLES.md §6). Use the Kotlin MCP client pattern (EXAMPLES.md §7). Tests assert the right tool is selected per question class.
- [ ] **T5 — Real TWR + FIFO in `calc/` (tests first).** Create `agents/midas/core/.../calc/Twr.kt` + `calc/Fifo.kt` (the `calc/` package does not exist yet — S1.4 left inline stubs). `TwrSpec` + `FifoSpec` with **hand-computed expected values** from reference portfolios (Bora-owned fixtures; 4-decimal precision). Wire into `PortfolioPerformanceTool`/`CostBasisTool` in `MidasTools.kt`, replacing the stubs. *(MWR + fee-allocation + the cost-basis tool wiring complete in S3.3.)*
- [ ] **T6 — Q&A component test (mocked constellation).** Component-level test of the Golem-Investment turn with Themis / iris-bff / theseus-mcp / Midas-core MCP edges Wiremock-stubbed: five canned questions → expected `envelope/v1` Block sequences. Measure p95 latency at the component level. Per planning-conventions §4, the live end-to-end-through-Iris run is Stream T.
- [ ] **T7 — Error path: portfolio_id not found (tests first + impl).** A Themis-resolved `portfolio_id` absent from the DB → Golem returns an envelope with a clean diagnostic block (Rule-6 `ResponseMessage`, not a 500). Spec asserts the user-visible error block shape.

## DONE (plan §Stage 3.2)

- [ ] Five canned Q&A questions produce the correct `envelope/v1` Block sequences at the mocked-component level (T6).
- [ ] `TwrSpec` + `FifoSpec` pass at 4-decimal precision against reference portfolios (T5).
- [ ] Component-level p95 < 5s for simple questions.
- [ ] Error path returns a clean diagnostic envelope (T7).
- [ ] (Demo, not gate) live turn through a running Iris → Themis → Golem-Investment → Arges answers a real question.

## Follow-ups (not in this stage)

- MWR, FIFO cost-basis tool wiring, fee allocation — S3.3.
- Live end-to-end + the `golem-investment` nightly integration context — Stream T.
