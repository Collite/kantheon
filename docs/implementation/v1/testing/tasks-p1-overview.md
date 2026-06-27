# Phase 1 — Component tier (real-dep, no cluster)

> **Reads with.** [`plan.md`](./plan.md) (Phase 1), [`../../../architecture/testing/architecture.md`](../../../architecture/testing/architecture.md) §2/§9, [`../../../architecture/testing/contracts.md`](../../../architecture/testing/contracts.md) §4, [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** A `componentTest` source set that runs Kotest specs against **real backing dependencies in Testcontainers** (no Kubernetes), wired into `ci.yml` to gate every PR and merge. First real-dep coverage for the two services whose correctness most depends on a real database (Charon↔Postgres, Brontes↔MSSQL). No service tag bump — this is test infrastructure.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **1.1** — `componentTest` wiring + vocabulary canon | `just test-component` green (smoke) on a PR; `just test-all` provably collects zero `@Tag("component")` specs; AGENTS.md §8 + CLAUDE.md §9 vocabulary updated | [`tasks-p1-s1.1-componenttest-wiring.md`](./tasks-p1-s1.1-componenttest-wiring.md) |
| **1.2** — First real-dep specs | `CharonPostgresComponentSpec` green locally + CI; `BrontesMssqlComponentSpec` green in CI, skipped locally (CI-gated — MSSQL is amd64-only) | [`tasks-p1-s1.2-realdep-specs.md`](./tasks-p1-s1.2-realdep-specs.md) |

## Sequencing

Strictly sequential.

```
Stage 1.1 ──► Stage 1.2
  wiring+canon   real-dep specs
```

## Pre-flight for the phase

- [ ] **Docker** available on dev machines and on the GH Actions `ubuntu-latest` runner (default — Testcontainers uses the host daemon).
- [ ] **No cross-repo dependency.** Phase 1 needs no olymp, no cluster. It can start immediately.
- [ ] Charon (`services/charon`) and Brontes (`workers/brontes`) build green under `just build-kt`.
- [ ] Branch per stage: `feat/p1-s1.1-componenttest-wiring`, then `feat/p1-s1.2-realdep-specs`.

## Aggregate progress

- [ ] **Stage 1.1** — `componentTest` source set + `just test-component` + CI step + isolation guard + vocabulary canon edits.
- [ ] **Stage 1.2** — Charon↔Postgres and Brontes↔MSSQL real-dep specs + shared container fixtures.

When both are checked, Phase 1 is DONE: `just test-component` gates every PR and merge; the policy in planning-conventions §4 (mocked-only *stages*) is provably preserved.
