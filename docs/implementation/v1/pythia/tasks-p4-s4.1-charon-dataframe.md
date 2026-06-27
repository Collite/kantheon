# Stage 4.1 — Charon integration + DataFrameNode

> **Phase 4, Stage 4.1.** ⚠️ **Execution blocked on `charon/v0.3.0` + `charon-mcp/v0.1.0`** (status 2026-06-26: only `charon/v0.1.0` tagged; the Charon arc's Phase 2/3 are open). This task list is written ahead per the parallel-planning allowance (plan §8); **do not start coding until the Charon gate lands** — see [`tasks-p4-overview.md`](./tasks-p4-overview.md) pre-flight.
>
> **Reads with.** [`tasks-p4-overview.md`](./tasks-p4-overview.md), [`plan.md`](./plan.md) §6 Stage 4.1, [`../../../architecture/pythia/architecture.md`](../../../architecture/pythia/architecture.md) §5 ("Handle table", sticky affinity), [`../../../architecture/pythia/contracts.md`](../../../architecture/pythia/contracts.md) §6 + §3a (PD-5), [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §1 (`CharonService`), §2 (legality matrix), §7 (Handle↔Location mapping), [`../../../design/pythia/Pythia-v1-Design.md`](../../../design/pythia/Pythia-v1-Design.md) §6.2 (materialisation policies), [`../../planning-conventions.md`](../../planning-conventions.md) §4.

## Goal

Pythia's data plane comes alive: PD-5 resume-time liveness probes + lazy re-materialisation; `CharonClient` (gRPC) driving the four Charon-backed handle kinds; a materialisation policy engine; `WorkerClient` + DataFrameNode on Polars-Worker (Steropes) session DFs; the planner gains DataFrame composition (lit up via capabilities-mcp, with SQL-only degradation); the IN-list>500 materialise path. **End state:** all specs green against a **Charon fixture-server** + **mocked Polars worker** (live Charon/worker = integration suite, §4).

## Pre-flight

- [ ] Phase 3 DONE — `pythia/v0.3.0`.
- [ ] Branch `feat/pythia-p4-s4.1-charon-dataframe`.
- [ ] Read `charon/contracts.md` §1 (`CharonService`: `Materialize`/`Stage`/`Copy`/`Evict`/`Describe`; `Location` union `seaweed`|`redis`|`worker_df`|`db_table`), §2 (legality matrix), §6 (the canonical schema fingerprint), §7 (the exact Handle↔Location mapping). Read the worker contract (`org.tatrman.worker.v1.WorkerService`, session DFs keyed `(session_id, df_name)`).

## Upstream closeout gate (CG — do before any T-task)

This stage builds entirely on the Charon (and, for the bonus METIS path, Metis) arcs. **Every CG box below must be green before T0 starts** — Stage 4.1 is the consumer that the Charon arc's exit gate exists to unblock. These are verification tasks, not implementation: confirm the upstream is genuinely closed, don't re-do its work. Mirror of the Charon-side checklist in [`../charon/tasks-p3-s3.2-mcp-bench-ship.md`](../charon/tasks-p3-s3.2-mcp-bench-ship.md) ("Pythia Phase 4.1 readiness checklist").

- [x] **CG1 — Charon Phase 1 truly closed (the latent blocker). ✅ MET 2026-06-26.** `charon/v0.1.1` re-tagged after the Charon Stage 1.4 **closeout** ([`../charon/tasks-p1-s1.4-closeout.md`](../charon/tasks-p1-s1.4-closeout.md) Part A) — review-006 R1–R8 verified (164 tests green), the CI fingerprint regen+diff guard live (`.github/workflows/ci.yml`), and `IntegritySpec` aligned to the shared cross-engine pin `shared/testdata/fingerprints/` (incl. `map.arrow`; private `fixtures/integrity/` deleted). This is what makes the **multi-batch `ArrowPipe` round-trip** correct and the **Kotlin↔Python↔Steropes fingerprint identity** CI-locked — Pythia's PD-5 drift detection and any multi-batch evidence move silently lose data otherwise. Verify: `git tag` shows `charon/v0.1.1` + the closeout Part A boxes are checked.
- [x] **CG2 — Charon Phase 2 closed. ✅ MET 2026-06-26.** `charon/v0.2.0` tagged — DB extract + ingest + `Describe(db_table)` (Stages 2.1/2.2), the connection registry (`charon-connections` ConfigMap) holds the v1 named connections Pythia reads, with `charon-db-credentials` sealed-secret env (Stage 2.3). **Pythia's internal PG is NOT a listed connection** (registry omits it; allow-list + lazily-validated). Note: the `pythia-evidence` bucket retention-tag *provisioning* (production 90 d / shallow 7 d lifecycle rules) is fabric-infra content + a live-cluster smoke — an integration carry-over, not a code gate; the `retention_tag` → S3 object-tag *flow* is covered (`DbMoveComponentSpec`).
- [x] **CG3 — Charon Phase 3 closed (the named gate). ✅ MET 2026-06-26.** `charon/v0.3.0` + `charon-mcp/v0.1.0` tagged. Green: all five gRPC RPCs; all four `Location` kinds; `Describe` liveness (`exists` + `schema_fingerprint`) for blob / db_table / worker_df; legality matrix complete + error-clean (`LegalityMatrixDocSpec`); fingerprint determinism (shared cross-engine pin); `move.*` capabilities + heartbeat + bench `cost_hints`. **Cross-engine `Stage(source → worker_df)` works for BOTH engines** — the POLARS path landed via the worker-arc `worker.v1 ImportDataFrame`/`DropWorkspaceEntry` RPCs added to Steropes at the Charon 3.1 closeout (Pythia 4.1 POLARS DataFrame composition unblocked). Verify: `git tag | grep charon` shows `charon/v0.3.0` + `charon-mcp/v0.1.0`.
- [x] **CG4 — Metis gate (for the 4.2 hand-off; bonus METIS staging).** `metis/v0.3.0` + `metis-mcp/v0.1.0` tagged ✅ (already met 2026-06-26), and the Charon **METIS `WorkerKind`** variant (Charon Stage 3.1 T7) is live so `Stage(source → worker_df{METIS})` works — needed by Stage 4.2, verified here so 4.2 doesn't re-block.
- [x] **CG5 — Substrate reachable.** Fork Phase 3 services confirmed (`theseus`/`theseus-mcp`/`steropes`/`kyklop` tags or sign-off, plan §2); the in-repo Polars worker (Steropes) reachable; `pythia-evidence` bucket reachable from the kantheon namespace.

> If any CG box is red, **stop** — the gap is an upstream arc's to close (Charon `tasks-review-006.md` / `tasks-p1-s1.4` / Phase 2–3, or fabric-infra provisioning), not Stage 4.1's. Flip the [`tasks-p4-overview.md`](./tasks-p4-overview.md) pre-flight + this stage's banner to ✅ only when all five are green.

## Tasks (TDD-shaped: T0 + each client spec written before its impl)

- [x] **T0 — PD-5 resume semantics (contracts §3a).**

  Implement resume-time handle liveness: on resume from any AWAITING_*, for each handle the resumed plan still needs, probe liveness (**Charon `Describe`** → `exists` + `schema_fingerprint`) and **lazily re-materialise dead handles** from the checkpointed move-spec recipe (Stage 1.2 PD-5 checkpoint fields). **Fingerprint drift** (re-materialised data differs from the checkpointed fingerprint) → a Rule-6 warning + a `LooseEnd` ("inputs changed during pause: <handle>"), **never a hard fail**, never silent epoch-mixing (contracts §3a).

  Test (kill-TTL-resume fixture, Charon fixture-server): a handle whose `Describe` reports `exists=false` is re-materialised from its recipe; a fingerprint mismatch yields the warning + LooseEnd and continues.

  Acceptance: `Pd5ResumeSpec` green.

- [x] **T1 — `CharonClient` (gRPC).**

  Implement `dataplane/CharonClient.kt` against `org.tatrman.charon.v1.CharonService` (gRPC-direct — the MCP wrapper is not on Pythia's path, contracts §6). Methods used: `Materialize`, `Stage`, `Copy`, `Evict`, `Describe`. Specs via a **Charon fixture-server** (an in-process gRPC test double returning `MoveResult`/`DescribeResult` fixtures — not Testcontainers; live Charon = integration suite).

  Acceptance: `CharonClientSpec` green for each RPC + the legality-matrix rejections (contracts §2 — e.g. an illegal source→target pairing surfaces the Charon error).

- [x] **T2 — Charon-backed handle kinds activate.**

  Activate the four handle kinds in `HandleTable` (Stage 2.2 had only `LiveQueryRef`+`PgResultSnapshot`): `WorkerSessionDF` (keyed `worker_pod, session_id, df_name`), `SeaweedArrowBlob`, `RedisArrowEntry`, `DbTableRef`. Map each to its Charon `Location` per **charon/contracts.md §7** (`SeaweedArrowBlob→SeaweedBlob`, `RedisArrowEntry→RedisEntry`, `WorkerSessionDF→WorkerSessionDf`, `DbTableRef→DbTable`); `LiveQueryRef`+`PgResultSnapshot` stay Pythia-internal (no mapping).

  Test: each handle kind resolves to the correct `Location` for a Charon call; the two internal kinds are never sent to Charon.

  Acceptance: `HandleLocationMappingSpec` green.

- [x] **T3 — Materialisation policy engine.**

  Implement the policy engine (design §6.2): the triggers that decide *when* Pythia calls Charon — **evidence-persist** (a load-bearing result → Seaweed at finalisation), **cross-engine staging** (a handle needed by a different engine, e.g. SQL result → worker DF for DataFrame ops), **TTL-approach** (a handle nearing its source TTL → persist). Charon never decides on its own (contracts §6) — Pythia issues the move.

  Test: each policy trigger fires the expected Charon call (Materialize/Stage/Copy) on a crafted state; no spurious moves otherwise.

  Acceptance: `MaterialisationPolicySpec` green.

- [x] **T4 — Sticky affinity + evidence persistence + GC.**

  Sticky-affinity scheduler hints (Stage 2.2 executor): children of a `WorkerSessionDF` handle carry the parent's `session_id` so Polars chains stay on-pod (architecture §5). Evidence persistence at finalisation → Seaweed bucket `pythia-evidence`, keys `{investigation_id}/{handle_id}.arrow`, `retention_tag` production (90 d) / shallow (7 d) (contracts §6). GC/evict transient handles on completion (`Evict`).

  Test: a DataFrame chain schedules on one session; finalisation persists load-bearing handles with the right retention tag; transient handles are evicted.

  Acceptance: `AffinityEvidenceGcSpec` green.

- [x] **T5 — `WorkerClient` + DataFrameNode.**

  Implement `dataplane/WorkerClient.kt` (`org.tatrman.worker.v1.WorkerService.Execute` streaming, session reuse) + the DataFrameNode `NodeExecutor` (dfdsl ops; session-DF chaining — output keyed `(session_id, df_name)`). Mocked worker in tests (Arrow IPC fixtures; live Steropes = integration suite).

  Test: a DataFrameNode runs a dfdsl op over a staged session DF and produces a `WorkerSessionDF` handle; a two-node chain reuses the session.

  Acceptance: `DataFrameNodeSpec` green.

- [x] **T6 — Planner gains DataFrame composition.**

  The capability lights up via capabilities-mcp search (the DataFrame capability is now registered); the planner (Stage 2.1) may compose DataFrameNodes. **SQL-only degradation** stays tested both ways: with the capability present the planner may use DataFrame ops; absent (or for SQL-sufficient questions) it produces SQL-only plans (the Phase 2 behaviour).

  Test (scripted planner + Wiremock capabilities-mcp): capability-present → a plan with a DataFrameNode validates; capability-absent → SQL-only plan, no DataFrame node.

  Acceptance: `PlannerDataFrameSpec` green.

- [x] **T7 — IN-list>500 materialise path + worked-example variant.**

  Activate the IN-list>500 path flagged in Stage 2.3 T2: a large id-list param materialises (Stage a `PgResultSnapshot`/result → a worker DF or Seaweed blob via Charon, then bind the downstream QueryNode against it). Re-run the **Nescafe-Maggi N3** step as a **DataFrameNode** variant (plan §6 Stage 4.1 T7) to exercise the cross-engine path end-to-end.

  Test: a >500 id-list materialises + binds (no inline-cap Rule-6 flag this time); the Nescafe-Maggi N3 DataFrame variant produces the same evidence as the SQL form.

  Acceptance: `MaterialiseInListSpec` + the N3-variant component spec green; `just test-kt pythia` green.

## DONE — Stage 4.1

- [x] All tasks checked; suite green against the Charon fixture-server + mocked worker.
- [x] Integration carry-overs recorded (live Charon moves, live Steropes session DFs, real Seaweed/Redis round-trips, real fingerprint cross-engine check — Charon arc already pins the cross-engine fingerprint in `shared/testdata/fingerprints/`).
- [x] CI green on `[pythia-p4-s4.1] charon + dataframe`.

## Library / pattern references

- **charon/contracts.md** §1 (`CharonService` RPCs), §2 (legality matrix), §6 (canonical fingerprint — Pythia reads it via `Describe`, never recomputes), §7 (Handle↔Location).
- **contracts §3a** (PD-5 resume), **§6** (evidence conventions); **architecture §5** (sticky affinity, materialisation).
- **worker contract** `org.tatrman.worker.v1.WorkerService` — `Execute` streaming, `(session_id, df_name)` keying.

## Out of scope

- Metis / ModelNode / forecast / simulation — Stage 4.2.
- Live Charon/worker round-trips — integration suite.
- `model.decompose.variance` — v1.5.
