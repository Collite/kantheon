# Charon P3 Stage 3.2 — charon-mcp + registration + bench + ship

> **Phase 3, Stage 3.2.** Closes the arc — tags `charon/v0.3.0` + `charon-mcp/v0.1.0`. **This is the Pythia Phase 4 pre-flight gate.**
>
> **Reads with.** [`plan.md`](./plan.md) §5 Stage 3.2, [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §3 (charon-mcp tool surface) + §9 (versions), [`../../../architecture/charon/architecture.md`](../../../architecture/charon/architecture.md) §8 (observability), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Why Pythia cares.** Pythia calls Charon over **gRPC directly** (the MCP wrapper is not on Pythia's path), but the `move.*` **ToolCapability registration** is what makes the data-plane capabilities discoverable in capabilities-mcp — which Pythia's planner searches when deciding DataFrame composition (pythia 4.1 T6). The **benchmark `cost_hints`** feed Pythia's budget projection. This stage's exit tags are Pythia 4.1's hard pre-flight.

## Goal

`tools/charon-mcp` (thin wrapper, zero logic); the five `move.*:v1` ToolCapability manifests + heartbeat into capabilities-mcp; a benchmark harness feeding `cost_hints`; observability completion; docs + tags. **End state:** `charon/v0.3.0` + `charon-mcp/v0.1.0` — Pythia Phase 4 unblocked.

## Pre-flight

- [x] Stage 3.1 DONE — worker endpoint + METIS variant + final matrix.
- [x] Branch `feat/charon-p3-s3.2-mcp-bench-ship`.
- [x] `capabilities-mcp` live (Phase 1 of the Themis/capabilities arc — `capabilities-mcp/v0.1.0` tagged) + `capabilities-client` lib available.
- [x] Read contracts §3 (the five `move.*` tool mappings; JSON↔proto, locations as structured JSON not stringified — Rule 7 spirit).

## Tasks (TDD-shaped: T1 tests-first)

- [x] **T1 — Tests first: `McpToolsSpec`.**

  JSON↔proto fidelity per tool (`move.materialize`/`move.stage`/`move.copy`/`move.evict`/`move.describe` — contracts §3): a JSON request maps to the right proto, the gRPC result maps back to JSON incl. `messages = 99` (Rule 6 pass-through); error pass-through (a gRPC `INVALID_ARGUMENT` surfaces as the MCP error shape with the `messages` detail). Locations are structured JSON, never stringified.

  Acceptance: spec compiles + fails.

- [x] **T2 — `tools/charon-mcp` module (thin wrapper).**

  Create `tools/charon-mcp` (ktor-configurator MCP base, streamable-HTTP `POST /mcp`, port 7252 per contracts §8). **Zero logic — review-enforced:** validate JSON → proto, one gRPC call to `CharonService`, proto → JSON. No move logic, no endpoint knowledge. `settings.gradle.kts` entry + CI.

  Acceptance: `McpToolsSpec` green; the module is a pure pass-through (reviewer confirms no business logic).

- [x] **T3 — `move.*:v1` ToolCapability manifests + heartbeat.**

  Author the five `manifests/move.*.yaml` ToolCapability manifests + register via `capabilities-client` heartbeat (warn-and-continue if the registry is down). Confirm they appear in capabilities-mcp `list()`. Capability ids follow the `<service>.<tool>:v1` convention (e.g. `charon.move.stage:v1`), `category: "charon"`.

  Test: heartbeat posts the five manifests; registry-unreachable degrades without failing boot.

  Acceptance: manifests visible in capabilities-mcp; heartbeat spec green.

- [x] **T4 — `bench/` harness + `cost_hints`.**

  A benchmark harness measuring rows/s + MB/s per legal pair on 1e5 / 1e6-row reference sets; results populate the manifests' `cost_hints` + the module README. (These `cost_hints` are what Pythia's `BudgetTracker` / planner reads to project move cost.)

  Acceptance: bench runs + emits per-pair throughput; `cost_hints` filled.

- [x] **T5 — Observability completion.**

  Metrics per architecture §8 (move counters by pair/result, bytes, rows, durations, fingerprint-mismatch counter); Grafana panel set; **trace nesting verified from a fixture caller** (a Charon move appears as a child span under a caller's trace — Pythia's investigation trace will nest Charon spans). 

  Acceptance: metrics emit (test meter registry); dashboard JSON checked in; trace nesting documented.

- [x] **T6 — Docs + tags + Pythia readiness sign-off.**

  READMEs (`services/charon`, `tools/charon-mcp`) + kantheon-architecture cross-refs; fold any contract divergences back into `contracts.md`. Run the **Pythia 4.1 readiness checklist** (below) and sign it off in the PR. **Tag `charon/v0.3.0` + `charon-mcp/v0.1.0`.** Update [`plan.md`](./plan.md) §9 (all boxes) and the Pythia [`tasks-p4-overview.md`](../pythia/tasks-p4-overview.md) pre-flight (flip the Charon gate to ✅) + [`tasks-p4-s4.1-charon-dataframe.md`](../pythia/tasks-p4-s4.1-charon-dataframe.md) banner.

  Acceptance: tags pushed; CI green on `[charon-p3-s3.2] mcp + bench + ship`.

## Pythia Phase 4.1 readiness checklist (sign off at T6)

Everything Pythia 4.1 consumes from Charon must be green:

- [x] **All five gRPC RPCs** reachable on `CharonService`: `Materialize`, `Stage`, `Copy`, `Evict`, `Describe` (Pythia `CharonClient`, pythia 4.1 T1).
- [x] **All four `Location` kinds** live + mapped from Pythia handles (pythia 4.1 T2; charon contracts §7): `SeaweedBlob` (P1), `RedisEntry` (P1), `DbTable` (P2), `WorkerSessionDf` (P3).
- [x] **`Describe` liveness probe** returns `exists` + `schema_fingerprint` for **every** kind Pythia holds — blob (P1), db_table (2.1 T6), worker_df (3.1 T4) — so PD-5 resume works for all (pythia 4.1 T0; pythia contracts §3a).
- [x] **Legality matrix** complete + error-clean (2.2 T5, 3.1 T6) — Pythia relies on `INVALID_ARGUMENT` for illegal pairs.
- [x] **Evidence path**: any→seaweed materialise works incl. retention-tag flow (pythia 4.1 T3/T4 evidence-persist). *(The `pythia-evidence` bucket + lifecycle-rule provisioning is a fabric-infra integration carry-over — not a code gate; the `retention_tag`→object-tag flow is covered by `DbMoveComponentSpec`/`WorkerMoveComponentSpec`.)*
- [x] **Cross-engine staging — both engines.** `Stage(any source → worker_df{METIS})` and `Stage(… → worker_df{POLARS})` are live (3.1; the POLARS `worker.v1 ImportDataFrame` RPC landed at the 3.1 closeout). Pythia 4.1's POLARS DataFrame composition (4.1 T6/T7) is unblocked.
- [x] **Fingerprint determinism** holds across DB-extract (2.1 T4) + worker (3.1 T5) so Pythia's drift detection is meaningful.
- [x] **METIS variant** (3.1 T7) — Charon side of pythia 4.2's Metis staging (bonus; unblocks 4.2).
- [x] **`move.*` capabilities registered** in capabilities-mcp (3.2 T3) + `cost_hints` from bench (3.2 T4) — pythia planner discovery + budget projection.

## DONE — Stage 3.2 → Phase 3 → the arc

- [x] charon-mcp pass-through green (8 `McpToolsSpec`); 5 `move.*` manifests + heartbeat; bench harness + fingerprint-mismatch counter + Grafana dashboard.
- [x] Pythia 4.1 readiness checklist signed off — **all worker paths (METIS + POLARS) live** after the 3.1 closeout added `worker.v1 ImportDataFrame`/`DropWorkspaceEntry` to Steropes. Evidence-bucket provisioning + live per-pair bench remain integration carry-overs.
- [x] **Tags `charon/v0.3.0` + `charon-mcp/v0.1.0`.** **Phase 3 DONE — Pythia Phase 4 fully unblocked** (both worker engines stage in).

## Library / pattern references

- **contracts §3** (charon-mcp tool surface), **§9** (version/tag contracts), **architecture §8** (observability).
- **`capabilities-client`** + the Themis/Charon heartbeat pattern; **ai-platform `EXAMPLES.md`** MCP-server section for the ktor-configurator MCP base.

## Out of scope

- Async moves + NATS completion events; predicate pushdown; Parquet/CSV/DuckDB kinds; provenance ledger — all v1.x (plan §7).
- Pythia's own `CharonClient` — that's Pythia 4.1 (pythia arc), not here (plan §6 cross-cutting).
