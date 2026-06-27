# Implementation

"What we're building right now." Phased plans, per-stage task lists, status reports, gap-closure documents, version-specific backlogs.

## Cross-cutting

- **[`planning-conventions.md`](./planning-conventions.md)** — task → stage → phase hierarchy. Applies to **all** planning in this repo. Read before producing new plans or task lists.

## Per version

- **[`v1/`](./v1/)** — the v1 implementation arc (active).

## What kinds of docs go where

| Where | What it contains |
|---|---|
| `implementation/` (this folder) | Cross-cutting conventions and tooling (e.g. `planning-conventions.md`). |
| `implementation/<version>/` | Per-version cross-cutting status: `next-steps.md`, ai-platform audits, gap-closure plans. |
| `implementation/<version>/<agent>/` | Per-agent implementation: `plan.md`, per-stage `tasks-*.md`, per-agent backlogs. |

## Up / across

- Up: [`../README.md`](../README.md) — top-level docs index.
- Across: [`../design/`](../design/) — *what* each agent is. [`../architecture/`](../architecture/) — *how* each agent is built.
