# Stage 3.2 — Workspace + receipts in PG

> **Phase 3, Stage 3.2.**
>
> **Reads with.** [`tasks-p3-overview.md`](./tasks-p3-overview.md), [`plan.md`](./plan.md) §"Stage 3.2", [`../../../architecture/hebe/architecture.md`](../../../architecture/hebe/architecture.md) §5.3 (workspace/receipts follow `fs.durability`, not the profile), [`../../../architecture/hebe/contracts.md`](../../../architecture/hebe/contracts.md) §4.3 (`V6__workspace.sql` + `V7__receipts.sql` DDL).

## Goal

The two new tables behind their seams: `workspace_files` (the markdown workspace — `MEMORY.md`, `IDENTITY.md`, `HEARTBEAT.md`, `daily/*.md` — with revision-based optimistic concurrency) and `receipts` (the NDJSON hash-chained log ported to PG, append-only, Ed25519-signed). Both are selected by **`fs.durability = ephemeral`** (only `k8s`), so the `WorkspaceStore`/`ReceiptsStore` seams carry filesystem **and** PG impls. After this stage the full Hebe loop runs on PG with **zero filesystem state besides logs**; `server` keeps file workspace + file receipts unchanged.

## Pre-flight

- [ ] **Stage 3.1 DONE** — PG memory backend + migration set in place.
- [ ] **Branch**: `feat/hebe-p3-s3.2-workspace-receipts`.
- [ ] Confirm the `WorkspaceStore` and `ReceiptsStore` seams (architecture §5.3 says they "already exist in the standalone design") — this stage extracts/implements the PG impls behind them, it does not re-architect.

## Tasks

- [ ] **T1 — Tests first: `WorkspaceStore` contract tests (both impls) + receipts chain-verify.**

  Create `WorkspaceStoreContractSpec` (run against **both** the filesystem impl and a faked-PG impl — a shared contract spec parameterised by impl): read/write/list a path; **revision-based optimistic concurrency** (a write with a stale `revision` is rejected; a fresh write bumps `revision`); `updated_by` recorded (`agent` | `console:<user>`).

  Create `ReceiptsChainSpec`: appending links `prev_hash = self_hash(seq-1)` (`genesis` for seq 1); `self_hash = sha256(canonical(payload) + prev_hash)`; **tamper detection** — mutating any payload breaks `--verify`; the Ed25519 `sig` validates against the instance signing key.

  Acceptance: specs written and failing. Commit `[hebe-p3-s3.2] failing workspace/receipts specs`.

- [ ] **T2 — `WorkspaceStore` seam + filesystem impl behind it.**

  Make `WorkspaceStore` the single entry point for workspace reads/writes; route the existing `~/.hebe/workspace/` filesystem behaviour behind it (local/personal/server unchanged). Selected by `workspace.backend` (resolved `Axes`).

  Acceptance: `local`/`server` workspace behaviour regression-green through the seam.

- [ ] **T3 — PG `workspace_files` impl (V6) with optimistic concurrency.**

  Author `V6__workspace.sql` exactly as contracts §4.3 (`path` PK, `content`, `revision int default 1`, `updated_at timestamptz`, `updated_by text`). Implement the PG `WorkspaceStore`: every write bumps `revision`; a write carrying a stale revision fails (optimistic concurrency). Unit-test against the fake driver.

  Acceptance: T1 `WorkspaceStoreContractSpec` passes for the PG impl (incl. the stale-revision rejection).

- [ ] **T4 — PG `receipts` impl (V7): hash chain + Ed25519, append-only.**

  Author `V7__receipts.sql` exactly as contracts §4.3 (`seq` identity PK, `ts`, `payload jsonb`, `prev_hash`, `self_hash`, `sig`). Implement the PG `ReceiptsStore` with the **same** hash-chain + Ed25519 algorithm as the file log (bouncycastle, `libs.versions.bouncycastle`); the signing key comes from the `SecretsStore` (`k8s` backend → K8s Secret per instance, Stage 2.3). The app role gets **no UPDATE/DELETE** on `receipts` (append-only — declared here, granted at provisioning Stage 3.3).

  Acceptance: T1 `ReceiptsChainSpec` passes for the PG impl (chain + tamper detection + signature).

- [ ] **T5 — Console workspace editor + `--verify` over either backend.**

  The web-console workspace editor (`:agents:hebe:modules:channels`) works against whichever `WorkspaceStore` impl is active. The `hebe memory show receipts --verify` command walks `seq` order on the PG backend with the same algorithm as the file log. On `k8s` (ephemeral FS) the console is the **sole** workspace-editing surface (architecture §5.3) — assert no filesystem fallback.

  Acceptance: console edits + `--verify` work on both backends; no FS write on `k8s`.

- [ ] **T6 — Maintenance routines green on PG.**

  Run the maintenance routines (daily digest, summariser, embedding refresh) against the PG backends end-to-end (memory + workspace + receipts), proving the full loop runs with zero filesystem state besides logs. Embedding refresh populates `memory_chunks.embedding` (the pgvector column from Stage 3.1).

  Acceptance: the three maintenance routines complete on PG; a `k8s`-profile run shows no FS state besides logs. PR `[hebe-p3-s3.2] workspace + receipts in pg`.

## DONE — Stage 3.2

- [ ] All six tasks checked.
- [ ] `WorkspaceStore`/`ReceiptsStore` seams carry filesystem **and** PG impls, selected by `fs.durability`/`workspace.backend`/`receipts.backend`.
- [ ] Optimistic concurrency on `workspace_files`; hash-chain + Ed25519 + tamper detection on `receipts`.
- [ ] Full Hebe loop runs on PG with zero filesystem state besides logs.
- [ ] `server` (file workspace + file receipts) regression-green.
- [ ] PR merged.

## Library / pattern references

- **contracts.md §4.3** — `V6`/`V7` DDL byte-for-byte.
- **architecture.md §5.3** — `fs.durability` (not the profile) decides PG; `server` keeps files.
- **bouncycastle** (`libs.versions.bouncycastle = 1.84`) — Ed25519 signing (same as the standalone file log).
- standalone §13 — the receipts hash-chain algorithm being ported.

## Out of scope for Stage 3.2

- Schema/role creation + the append-only GRANTs (Stage 3.3 provisioning).
- Real-PG verification (integration suite).
- The Jib image / Kustomize (Stage 3.3).
