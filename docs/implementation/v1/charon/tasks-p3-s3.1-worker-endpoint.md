# Charon P3 Stage 3.1 — Polars Worker endpoint (+ METIS variant)

> **Phase 3, Stage 3.1.** The worker edge — the last `Location` kind Pythia needs.
>
> **Reads with.** [`plan.md`](./plan.md) §5 Stage 3.1, [`../../../architecture/charon/contracts.md`](../../../architecture/charon/contracts.md) §1 (`WorkerSessionDf`, `WorkerKind`), §2 (Stage rows), §6 (worker paths), §8 (`charon.worker.steropes.*`), [`../../planning-conventions.md`](../../planning-conventions.md) §4.
>
> **Why Pythia cares (critical for 4.1).** Pythia 4.1's DataFrameNode runs on Polars-Worker (Steropes) session DFs that **Charon stages data into** via `Stage`. This stage delivers the `WorkerSessionDf` `Location`, the `Stage` RPC, and `Describe`/`Evict` for worker DFs — i.e. cross-engine staging + the worker-handle PD-5 liveness probe. The **METIS `WorkerKind`** variant (T7) additionally unblocks Pythia 4.2 (Charon stages a handle → Metis session DF for `Fit`).

## Goal

`WorkerEndpoint` stages any legal source into a Polars-Worker session DF and reads worker DFs back out; `Stage` complete (incl. cross-session/engine); `Describe`/`Evict` for worker DFs; the METIS worker-kind variant; legality matrix final review. **End state:** seaweed→worker→seaweed round-trip component-green (mocked worker); matrix doc confirmed against impl. (Live in-K3s round-trip vs the real `steropes`/`worker-polars` pod → integration suite, §4.)

## Pre-flight

- [x] **Phase 2 closed** — `charon/v0.2.0`.
- [x] **Worker workspace read-out verified** — scan-plan over a workspace DF streams `ResultBatch` out, **or** the `ReadWorkspace` RPC is available (plan §5 pre-flight / §6). Per [pythia plan §2](../pythia/plan.md), the worker read-out was verified at fork Stage 4.1 T4 (Steropes baseline) — re-confirm the path against the in-repo `workers/steropes` worker.
- [x] Branch `feat/charon-p3-s3.1-worker-endpoint`.
- [x] Read the worker contract (`org.tatrman.worker.v1.WorkerService.Execute` — stage-in via `assign_to_workspace=df_name`, session keyed `(session_id, df_name)`); contracts §6 worker-path conventions; the Metis workspace RPCs ([metis contracts §1.3](../../../architecture/metis/contracts.md)) for the METIS variant.

## Tasks (TDD-shaped: T1 tests-first)

- [x] **T1 — Tests first: `WorkerEndpointSpec` (gRPC fixture worker).**

  Against an in-process gRPC **fixture worker**: stage-in plan construction (`Execute(plan, session_id, assign_to_workspace=df_name)`); read-out scan; session reuse (two stages into the same `session_id` reuse the session); workspace-cap error mapping (`workspace_cap_exceeded` → `RESOURCE_EXHAUSTED`). 

  Acceptance: spec compiles + fails.

- [x] **T2 — `WorkerEndpoint` (Target via `Stage`; Source via scan-out).**

  Implement `endpoints/WorkerEndpoint.kt` per the verified read-out path: as a **Target**, stage Arrow into a session DF (`assign_to_workspace`); as a **Source**, scan the workspace DF out to an Arrow stream (feeds `ArrowPipe`). Keying exactly `(session_id, df_name)` (contracts §1 — matches Steropes).

  Acceptance: `WorkerEndpointSpec` Target+Source paths green.

- [x] **T3 — `Stage` RPC complete.**

  Wire the `Stage` RPC for **any source → worker session** (legality matrix §2: the `worker_df` *target* column is all `Stage`). Include **cross-session / cross-engine** staging (worker→worker — e.g. POLARS session A → session B). `MaterializeRequest` still rejects a `worker_df` target (Materialize target MUST be seaweed|redis|db_table, contracts §1) — `Stage` is the only way into a worker.

  Test: seaweed→worker, redis→worker, db→worker, worker→worker all stage; a `Materialize` with worker_df target → `INVALID_ARGUMENT`.

  Acceptance: `Stage` component suite green.

- [x] **T4 — `Describe`/`Evict` for worker DFs.**

  `Describe(worker_df)` → `GetStatus`-style stats (`exists`, schema_fingerprint, row_count, `expires_at` from the session TTL); `Evict(worker_df)` → drop the DF. **`Describe(worker_df)` is the Pythia PD-5 liveness probe for worker handles** — `exists=false` on a TTL'd-out session tells Pythia to re-materialise from the checkpointed recipe ([pythia contracts §3a](../../../architecture/pythia/contracts.md)).

  Test: `Describe` on a live DF returns fingerprint + expiry; on an evicted/expired DF `exists=false`; `Evict` is idempotent.

  Acceptance: worker `Describe`/`Evict` spec green.

- [x] **T5 — Component test: seaweed→worker→seaweed round-trip.**

  Mocked worker + mocked Seaweed: stage a Seaweed blob into a worker DF, read it back out, write to Seaweed — assert **byte-identical schema fingerprint** end-to-end through the endpoint wiring (the cross-engine fingerprint identity from Stage 1.2 must hold across the Kotlin↔worker boundary). 

  Acceptance: round-trip component spec green.

- [x] **T6 — Legality matrix final review.**

  Confirm the contracts §2 matrix against the implementation with a **table-driven test that generates the matrix doc table** (every legal cell exercised, every illegal cell `INVALID_ARGUMENT`). Update contracts §2 if any cell diverged. This is the matrix Pythia's data plane trusts.

  Acceptance: generated matrix matches contracts §2; suite green.

- [x] **T7 — METIS `WorkerKind` endpoint variant.**

  Activate the `METIS` worker kind (contracts §1 `WorkerKind { POLARS, METIS }`; plan §7 reserved slot — already touched by the Metis arc per repo history "metis P3: Charon METIS activation"). The `WorkerEndpoint` stages a source into a **Metis** workspace DF (Metis `ImportDataFrame` stream / workspace RPCs, [metis contracts §1.3](../../../architecture/metis/contracts.md)) so a `Stage(source → WorkerSessionDf{worker_kind: METIS})` lands data Metis `Fit` can consume. **This delivers the Charon side of Pythia 4.2's "Charon stages handle → Metis session DF".**

  Test (mocked Metis worker): `Stage(seaweed → worker_df{METIS})` imports the DF; `Describe(worker_df{METIS})` reports it; reconcile the keying with Metis's `(session_id, df_name)`/model-name conventions.

  Acceptance: METIS-variant spec green; `just test-kt services:charon` green.

## DONE — Stage 3.1

- [x] seaweed→worker→seaweed round-trip green (METIS); matrix doc confirmed; METIS variant green.
- [x] `Stage`/scan-out/`Describe`/`Evict` live for **both METIS and POLARS** (full parity).
- [x] **POLARS stage-in gap CLOSED (2026-06-26):** added `ImportDataFrame`/`DropWorkspaceEntry` to `worker.v1` + Steropes (`workers/steropes`: `import_data_frame`/`drop_workspace_entry` + `WorkspaceStore.drop`, 6 pytest green); `PolarsWorkerGateway` drives them; charon `WorkerEndpointSpec`/`WorkerMoveComponentSpec` round-trip POLARS stage-in. **Pythia 4.1 POLARS DataFrame staging unblocked.**
- [x] Integration carry-overs recorded (live Steropes/Metis round-trip, real workspace caps).
- [x] CI green on `[charon-p3-s3.1] worker endpoint`.

## Library / pattern references

- **worker contract** `org.tatrman.worker.v1.WorkerService` (Execute / assign_to_workspace / session keying), **contracts §1** (`WorkerSessionDf`, `WorkerKind`), **§2** (Stage rows), **§6** (worker paths).
- **metis contracts §1.3** (workspace RPCs — METIS variant), **Stage 1.2 `Integrity.kt`** (cross-engine fingerprint across the worker boundary).

## Out of scope

- `charon-mcp` wrapper + capability registration + bench + ship — Stage 3.2.
- DuckDB worker kind — v1.x (plan §7).
