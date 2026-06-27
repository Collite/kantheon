# Phase 5 — Kleio agent (NotebookLM)

> **Reads with.** [`plan.md`](./plan.md) §7 (Phase 5), [`../../../architecture/kleio/architecture.md`](../../../architecture/kleio/architecture.md) §5 (component diagram) + §1 outcome 5, [`../../../architecture/kleio/contracts.md`](../../../architecture/kleio/contracts.md) §8 (`kleio.proto`) + §5 (citation ↔ envelope grounding) + §9 (additive `capabilities/v1` enums), [`../golem/architecture.md`](../../../architecture/golem/architecture.md) (the Koog turn/envelope patterns Kleio borrows).

## Phase deliverable (deployable)

`agents/kleio` — a Koog graph: **scope-to-mart → graph-primary retrieve → grounded synthesis (Prometheus) → `envelope/v1` with per-claim `BlockProvenance` + Drilldown-to-source**; artifact generation (summary / FAQ / timeline / briefing); the `KNOWLEDGE` intent in Themis + the Iris notebook picker. Tag **`kleio/v0.1.0`** — the DocWH becomes a live constellation citizen.

> **Soft Spine dependencies (the only ones in the arc):** Themis P3 (`KNOWLEDGE` intent — **met**, `themis/v0.2.0`) and an Iris notebook surface. Kleio can land **callable directly** with Themis wiring as the final task (S5.3). Prometheus chat reachable is the live pre-flight.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **5.1** — kleio proto + Koog graph + grounded turn | A grounded mart turn renders cited `envelope/v1` blocks against mocks | [`tasks-p5-s5.1-grounded-turn.md`](./tasks-p5-s5.1-grounded-turn.md) |
| **5.2** — artifacts + capabilities registration | Artifacts generate; Kleio registered + discoverable | [`tasks-p5-s5.2-artifacts-registration.md`](./tasks-p5-s5.2-artifacts-registration.md) |
| **5.3** — Themis routing + eval + ship | Eval gate green; E2E smoke Iris→Themis→Kleio; tag `kleio/v0.1.0` | [`tasks-p5-s5.3-themis-eval-ship.md`](./tasks-p5-s5.3-themis-eval-ship.md) |

## Sequencing

```
Stage 5.1 ──► 5.2 ──► 5.3
 grounded turn   artifacts + registration   themis routing + eval + ship
```

## Pre-flight for the phase (plan §7)

- [ ] **Phase 4 DONE** — `kallimachos-mcp` live (`kallimachos-mcp/v0.1.0`); `library.*` callable under RLS.
- [ ] **Themis P3 routing** available (the `KNOWLEDGE` intent — met, `themis/v0.2.0`) **or** land Kleio callable directly with the Themis wiring as the final task (S5.3 T1).
- [ ] **Prometheus chat** reachable (the grounded-synthesis LLM egress) — Wiremock'd in unit tests.
- [ ] An Iris notebook-picker surface (soft; the picker binding is S5.2 T5).

## Testing policy

Mocked unit/component (architecture §13): `KleioStrategySpec` (Koog **mock executor** + mocked `getContext`) asserting every cited claim maps to a retrieved node; `ArtifactNodeSpec`; the eval corpus (grounded-citation faithfulness, NO_GROUNDING honesty, mart-scope leakage negatives). The in-K3s Iris→Themis→Kleio e2e is the integration suite (mock where the Spine isn't live).

## Aggregate progress (plan §11)

- [ ] **5.1** kleio proto + Koog graph + grounded turn.
- [ ] **5.2** artifacts + capabilities registration.
- [ ] **5.3** Themis routing + eval + ship. **P5 — `kleio/v0.1.0`.**

When all three are checked, push the tag. **The DocWH is a live constellation citizen — Kleio arc complete.**

## Up / across

- Up: [`./README.md`](./README.md). Neighbour: [`tasks-p4-overview.md`](./tasks-p4-overview.md).
- Cross-arc: **Themis** (`KNOWLEDGE` intent + route-to-Kleio, S5.3 T1) and **capabilities/v1** (additive `KNOWLEDGE_QA`/`KNOWLEDGE` enums, S5.2 T4).
