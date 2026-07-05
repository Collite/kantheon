# Deployment & Testing — Task lists (overview / management doc)

> **What this is.** The task-management index for the deploy-test program. Structures the four workstreams (D/T/C/R) into TDD-first checkbox task lists across **both repos**. Reads with [`master-plan.md`](./master-plan.md) (plan + architecture) and [`../../../architecture/deploy-test/contracts.md`](../../../architecture/deploy-test/contracts.md) (the interface spec every task builds to).
>
> **Repo tags.** Each task is tagged **[K]** (kantheon) or **[O]** (olymp). Cross-repo tasks list both. The olymp repo is at `~/Dev/collite-gh/olymp`; its companion pointers live in olymp `docs/` (extend `plan.md` Phase 7 + `test-harness.md`).
>
> *Created 2026-06-27. Owner: Bora. §7 decisions of the master plan are resolved.*

---

## 0. The TDD-first rule (every task list obeys this)

Per master-plan §6, every implementation task follows **write → local → bp-dsk**:

1. **Write the tests first** — component (Kotest + `component-testkit` Testcontainers) and/or integration (`@RequiresContext` + WireMock) **before** the code/wiring. Expected results defined up front.
2. **Run locally** — `just test-component`, or integration via a **k3d** context (`infra-up <ctx> <id> <k3d-ctx>`), or Testcontainers.
3. **Run on bp-dsk** — deploy/sync the needed services, then run the same specs via `infra-up <ctx> <id> dsk`.

A task is DONE only when its specs are green **both locally and on bp-dsk** (where a cluster is needed). Stage merges still gate on mocked unit tests (planning-conventions §4); the component/integration greens are this program's deliverables.

---

## 1. Workstream → stage → task-list map

| WS | Stage | Task list | Repos | Status |
|---|---|---|---|---|
| **D — Deployment** | D1 — Helm library chart + chart migration | [`tasks-d1-chart-library.md`](./tasks-d1-chart-library.md) | K | **done** (2026-07-05) |
| | D2 — Author the 22 missing charts + publish images | [`tasks-d2-charts-images.md`](./tasks-d2-charts-images.md) | K | **done** (2026-07-05; T6 image-push handed off) |
| | D3 — bp-dsk ArgoCD apps + platform deps (waves 1–7) | [`tasks-d3-bp-dsk-apps.md`](./tasks-d3-bp-dsk-apps.md) | O (+K descriptors) | **query-path chunk authored** (2026-07-05; single-ns; waves 1–2 + platform; live sync + waves 3–7 pending) |
| **T — TPC-DS** | T1 — `test-pg` server + `tpc-ds-1g` + load Job | [`tasks-t1-test-pg-load.md`](./tasks-t1-test-pg-load.md) | O (+K DDL) | written |
| | T2 — Ariadne TPC-DS model + curated queries + `pg-tpcds` profile | [`tasks-t2-model-connection.md`](./tasks-t2-model-connection.md) | K | written |
| **C — Test suite** | C1 — Component-tier real-dep matrix | [`tasks-c1-component-matrix.md`](./tasks-c1-component-matrix.md) | K | written |
| | C2 — Integration contexts (incl. `tpcds-query`) + run-set | [`tasks-c2-integration-contexts.md`](./tasks-c2-integration-contexts.md) | K (+O contexts) | written |
| **R — bp-dsk runs** | R1 — `--kube dsk` run mode + reconcile-boundary | [`tasks-r1-bp-dsk-run-mode.md`](./tasks-r1-bp-dsk-run-mode.md) | O (+K wiring) | written |

All eight task lists are written. Execution order (master-plan §5): **C1 + T1 start now** (no full estate needed) · **D1→D2→D3** stands the estate up (MP-1 at D3 wave 3) · **T2** then makes TPC-DS queryable (MP-2) · **R1** opens the bp-dsk run mode · **C2** runs the run-set incl. `tpcds-query` green on bp-dsk (MP-4) → release-tag sweep.

---

## 2. Sequencing & mergepoints (from master-plan §5)

```
D1 (chart lib) ──► D2 (charts+images) ──► D3 (bp-dsk apps, waves) ──► full estate live
                                                  │ (query path incl. Arges = MP-1)
T1 (test-pg+load) ───────────────────────────────┴──► T2 (model+queries) ──► tpcds queryable (MP-2)
C1 (component matrix) ── start NOW, no cluster ──► (MP-3)
R1 (--kube dsk + boundary) ──────────────────────► C2 (integration incl. tpcds-query) ──► GREEN on bp-dsk (MP-4)
```

- **C1 starts immediately** (no cluster) — fill the component matrix in parallel with everything.
- **R1's reconcile-boundary verification is the hard pre-task** for any bp-dsk integration run.
- **MP-1** (query path + Arges live on bp-dsk, via D3) unblocks T1's load target and the `tpcds-query` context.
- **MP-4** = C2's `tpcds-query` (+ run-set) green via `infra-up --kube dsk`, then the release-tag sweep (contracts §9).

---

## 3. Cross-repo coordination

- Every new `@RequiresContext("x")` [K] needs a `test-contexts/x/` [O] — guarded by `ContextNameRegistrySpec`. Add rows in lockstep.
- Every new bp-dsk app = a `<module>/k8s` chart [K] + a `clusters/bp-dsk/apps/<module>/` dir [O], per the deploy descriptor (contracts §2.5).
- olymp-side stages extend olymp `docs/plan.md` Phase 7 + `test-harness.md`; mirror a pointer there when each [O]-heavy list lands.

---

## 4. Definition of DONE (program)

- [ ] Full constellation reconciled on bp-dsk (landing in; backstage/kallimachos-browse best-effort).
- [ ] `tpc-ds-1g` on `test-pg` queryable through theseus→…→arges; the 4 curated queries return correct SF1 results.
- [ ] Component matrix green in CI on every PR.
- [ ] Integration run-set incl. `tpcds-query` green via `infra-up --kube dsk` (nightly continues on bp-olymp01).
- [ ] Deferred v1 release tags cut (contracts §9).
