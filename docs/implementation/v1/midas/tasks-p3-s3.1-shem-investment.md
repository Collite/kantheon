# Stage 3.1 — Golem-Investment Shem + curated queries

> **Phase 3, Stage 3.1.** Assemble and register the `golem-investment` Shem so Themis routes investment questions to it. The pod becomes reachable and registered; the Q&A *answer* path goes green in S3.2.
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md) §0 (reconciliations — **read first**), [`plan.md`](./plan.md) §Stage 3.1, [`../../../architecture/midas/contracts.md`](../../../architecture/midas/contracts.md) §9.1 (manifest — **apply the AREA_QA rename**), [`../../../architecture/midas/architecture.md`](../../../architecture/midas/architecture.md) §6.4.
> **Template (mirror this).** `agents/golem/shems/golem-ucetnictvi` + `agents/golem/src/main/kotlin/org/tatrman/kantheon/golem/shem/{ShemAssembler,ShemContext,ShemOverlay,ShemRegistration}.kt` + the test `agents/golem/src/test/.../shem/GolemUcetnictviBundleSpec.kt`. Ariadne: `services/ariadne/README.md` (`ResolveArea`, `ListQueries`, `GetModel`).

## Goal

`golem-investment` is an **assembled Shem** (not a mounted YAML): an `investment` area-def + curated `q.midas.*` queries in `ai-models`, resolved via Ariadne `ResolveArea`→`GetModel`, plus the thin overlay + template constants. It registers in `capabilities-mcp` as `AREA_QA`, and Themis routes the five example questions to it with confidence > 0.7. The pod boots and heartbeats; it does **not** answer yet (S3.2).

## Pre-flight

- [ ] Overview §0 reconciliations read (esp. assembled-Shem + AREA_QA + Ariadne-serves-queries).
- [ ] Themis routing live — `themis/v0.2.0` (✓).
- [ ] Ariadne live and serving `ResolveArea`/`ListQueries`/`GetModel` (fork Stage 2.1 ✓).
- [ ] `golem-ucetnictvi` assembly path green (the reference; `GolemUcetnictviBundleSpec` passes).
- [ ] Branch `feat/p3-s3.1-golem-investment-shem` from `main`.

## Tasks

- [ ] **T1 — `investment` area-def + model in `ai-models` (Bora-owned content).** Author the `investment` area under `ai-models/model-ttr/areas/investment.ttrm` (`AreaDef`, modeler ≥ 0.7.0) mapping the area → its packages, mirroring the `accounting`/`ucetnictvi` area. The packages must cover the entities in contracts §9.1: `clients`, `portfolios`, `assets`, `transactions`, `mv_position_current` (Position). **Tests first:** an Ariadne `ResolveAreaSpec`-style assertion (mirror `services/ariadne/.../grpc/ResolveAreaSpec`) — `resolveArea("investment")` returns the expected packages, description, tags, `found=true`.
- [ ] **T2 — Curated `q.midas.*` queries in `ai-models` (Bora-owned content).** Author `q.midas.positions_current`, `q.midas.transactions_recent`, `q.midas.dividends_period`, `q.midas.fees_period`, `q.midas.realised_pnl_period` as model queries served by Ariadne `ListQueries` (NOT ai-platform metadata-mcp — overview §0). Each: name, parameter slots, target connection **`pg-midas`**, SQL template against the `midas` schema/MVs. Use `{name}`-style named params (the Proteus parameter-bridge, restored in Golem S2.4, rewrites them). **Tests first:** Ariadne `ListQueries` returns all five with the declared params.
- [ ] **T3 — `shem-investment.yaml` overlay (apply the AREA_QA rename).** Write `agents/golem/shems/golem-investment/shem-investment.yaml` from contracts §9.1 **with the renames**: `agent_kind: AREA_QA`; `area_name: Investment`; `area_entities:` (was `domain_entities`); `area_terminology:` (was `domain_terminology`, keep ROI/TWR/MWR/NAV/FIFO/Realised+Unrealised P&L/Base currency); `capability_refs` use **`theseus`** query tools (overview §0) + the five `midas.*:v1`; `preferred_queries` reference the T2 `q.midas.*` ids. Keep `intent_kinds_supported: [PROCEDURAL]`, `example_questions`, `counter_examples`, `style_addendum` (no correctness-affecting knowledge in `style_addendum` — discipline rule), `locale_defaults`. Mirror `golem-ucetnictvi`'s overlay file structure.
- [ ] **T4 — Assemble the Shem (mirror golem-ucetnictvi).** Wire `golem-investment` into the `ShemAssembler` path: resolve `areas:[investment]` → packages (`ResolveArea`) → `GetModel` → merge the overlay + template constants into a `ShemContext`. **Tests first:** a `GolemInvestmentBundleSpec` mirroring `GolemUcetnictviBundleSpec` — assert the assembled bundle carries the five entities, the five terminology terms, the five queries, and the five `midas.*:v1` capability refs.
- [ ] **T5 — Golem-Investment pod + capabilities registration.** Deploy a `golem-investment` instance via the Golem **Helm chart** (`agents/golem/k8s/` — the Shem-mount capability landed in Golem P4 S4.4; mirror the `golem-ucetnictvi` deploy in [`../golem/tasks-p4-s4.4-ucetnictvi-deploy.md`](../golem/tasks-p4-s4.4-ucetnictvi-deploy.md)), supplying chart values that mount/select the `investment` Shem. Self-register via `shared/libs/kotlin/capabilities-client` `startupRegister` + 30s heartbeat (EXAMPLES.md §4b). **Tests first:** a registration spec against a Wiremock'd `capabilities-mcp` asserting one `AgentCapability` (`agent_kind=AREA_QA`, `agent_id=golem-investment`) is emitted; live registration is warn-and-continue.
- [ ] **T6 — Themis routing smoke (tests first).** Kotest/Wiremock spec: Themis (fixture LLM, Layer-1 rule-based on `description_for_router` + `example_questions`) routes "what is the current AAPL position in Smith's portfolio" and the other four example questions to `golem-investment` with confidence > 0.7; a counter-example ("Show me HR headcount") does **not** route to it. Deploy + manual route confirmation is a demo step, not the gate.

## DONE (plan §Stage 3.1)

- [ ] `ResolveArea("investment")` + `ListQueries` serve the area + five queries (T1/T2 specs green).
- [ ] `GolemInvestmentBundleSpec` green — the Shem assembles with entities/terminology/queries/capabilities.
- [ ] `capabilities-mcp.get(golem-investment)` would return the `AREA_QA` manifest (registration spec green; live heartbeat warn-and-continue).
- [ ] Themis routes the five example questions to `golem-investment` (>0.7) and rejects a counter-example (T6 spec green).
- [ ] Pod boots + registers; it does **not** answer Q&A yet (S3.2).

## Follow-ups (not in this stage)

- The answer path (graph wiring + MCP/query execution) — S3.2.
- Sweep `contracts.md` §9.1 to the AREA_QA / theseus / Ariadne-queries reconciliation (overview §5).
