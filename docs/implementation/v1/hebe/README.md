# Hebe — implementation

- [`plan.md`](./plan.md) — the Kantheon integration arc: P1 build merge → P2 profiles → P3 Postgres & instances → P4 constellation client (scheduled turns via iris-bff).
- [`standalone/`](./standalone/) — completed standalone-era M0–M10 task lists ([`v1-tasks.md`](./standalone/v1-tasks.md) + [`tasks/`](./standalone/tasks/)). Historical record; the code they produced is what moved into `agents/hebe` on 2026-06-12.

## Task lists (written 2026-06-25 — all four phases)

Per the planning conventions, each phase has an overview + per-stage TDD task lists (6–8 tasks, tests-first, library references, checkboxes).

| Phase | Deliverable | Overview | Stages |
|---|---|---|---|
| **P1** — Build & repo citizenship | Hebe in the kantheon root build; `org.tatrman.kantheon.hebe.*`; tag `hebe/v0.1.0` | [`tasks-p1-overview.md`](./tasks-p1-overview.md) | [1.1 gradle merge](./tasks-p1-s1.1-gradle-merge.md) · [1.2 package rename](./tasks-p1-s1.2-package-rename.md) |
| **P2** — Axes & profiles | Axis model + four presets; `local` byte-for-byte; offline tolerance; tag `hebe/v0.2.0` | [`tasks-p2-overview.md`](./tasks-p2-overview.md) | [2.1 axis model](./tasks-p2-s2.1-axis-model.md) · [2.2 llm-gateway](./tasks-p2-s2.2-llm-gateway.md) · [2.3 security split](./tasks-p2-s2.3-security-split.md) · [2.4 posture+otel](./tasks-p2-s2.4-posture-otel.md) · [2.5 offline tolerance](./tasks-p2-s2.5-offline-tolerance.md) |
| **P3** — Postgres & instances | Hebe pod on K3s; PG memory/workspace/receipts; tags `hebe/v0.3.0` + `capabilities-mcp/v0.2.0` | [`tasks-p3-overview.md`](./tasks-p3-overview.md) | [3.1 pg memory](./tasks-p3-s3.1-pg-memory.md) · [3.2 workspace+receipts](./tasks-p3-s3.2-workspace-receipts.md) · [3.3 instance deploy](./tasks-p3-s3.3-instance-deploy.md) · [3.4 cap-mcp registration](./tasks-p3-s3.4-capabilities-registration.md) |
| **P4** — Constellation client | Scheduled routine → iris-bff turn → Telegram delivery; tag `hebe/v0.4.0` | [`tasks-p4-overview.md`](./tasks-p4-overview.md) | [4.1 iris-bff client](./tasks-p4-s4.1-iris-bff-client.md) · [4.2 routine + delivery](./tasks-p4-s4.2-routine-delivery.md) |

> **Stream-B order (master-plan):** Fork P5 → **Hebe** → Kleio. P1–P3 have no Spine dependency; **P4 is gated by iris-bff (Iris arc ≥ Phase 2 / master-plan M3)**. Testing is mocked-unit only inside stages (planning-conventions §4); live/integration verification is the separate suite.
