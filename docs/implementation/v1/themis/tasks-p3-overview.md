# Phase 3 — Themis routing layer + Iris co-design

> **Reads with.** [`plan.md`](./plan.md) §5 (Phase 3 description), [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md) §6.2 (Koog graph at Phase 3), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md) §1.2 (themis/v1 routing extensions) + §1.3 (envelope/v1 RoutingPickChip) + §8 (prompts), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** Themis with the four-layer routing cascade live. `RoutingDecision`, `IntentKind`, `Profile`, `RefusalWithGaps`, `MultiQuestionDetected` populated end-to-end. Iris BFF stub round-trips chip picks against a fixture LLM. CI-enforced routing eval gate. Tag `themis/v0.2.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **3.1** — Proto extensions | All routing-related proto types generate clean Kotlin bindings; existing Phase 2 tests still pass (additive change) | [`tasks-p3-s3.1-proto-extensions.md`](./tasks-p3-s3.1-proto-extensions.md) |
| **3.2** — `classifyIntentKind` + `detectMultiQuestion` | Two new Koog nodes; `Resolution.intent_kind` populated correctly on Czech + English fixtures; `MultiQuestionDetected` fires on compound questions | [`tasks-p3-s3.2-intent-multiquestion.md`](./tasks-p3-s3.2-intent-multiquestion.md) |
| **3.3** — `routeToAgent` four-layer cascade | Layers 0/1/2/3 each exercised by fixture-driven tests; `CapabilitiesReadClient` integrated with fail-fast boot | [`tasks-p3-s3.3-route-to-agent.md`](./tasks-p3-s3.3-route-to-agent.md) |
| **3.4** — Profile + `RefusalWithGaps` + corpus skeleton | Per-profile graph traversal verified by snapshot tests; STRICT-mode `RefusalWithGaps` reachable; routing corpus skeleton committed | [`tasks-p3-s3.4-profile-refusal.md`](./tasks-p3-s3.4-profile-refusal.md) |
| **3.5** — Eval harness + CI gates | Harness extended for intent_kind + routing checks; CI gate live; Layer-1 hit-rate ≥ 60% on the populated corpus (Bora content fill in parallel) | [`tasks-p3-s3.5-eval-ci.md`](./tasks-p3-s3.5-eval-ci.md) |
| **3.6** — Iris stub + observability + cutover | RoutingPickChip wired in envelope/v1; Iris BFF stub round-trips a chip pick end-to-end; Grafana dashboard live; design.md updated; tag pushed | [`tasks-p3-s3.6-iris-cutover.md`](./tasks-p3-s3.6-iris-cutover.md) |

## Sequencing

Strict by default (each stage builds on the previous), with two parallelisable seams:

```
Stage 3.1 ──► 3.2 ──► 3.3 ──► 3.4 ──► 3.5 ──► 3.6
                                       ▲
                                       │
                       Bora-side corpus content fill (parallel; doesn't gate 3.4)
```

The eval-corpus content fill (Bora-owned task) can start as soon as Stage 3.4's skeleton lands. It is a soft-blocker on Stage 3.5's "Layer 1 hit-rate ≥ 60%" criterion.

## Pre-flight for the phase

- [ ] **Phase 2 DONE** — `themis/v0.1.0` tagged; eval-gate green.
- [ ] **capabilities-mcp running** in K3s with the seed `pythia` + `golem-erp` fixtures from Phase 1 Stage 1.4 — Themis's `routeToAgent` Layer 1 needs them.
- [x] **An Iris BFF co-design owner named** for Stage 3.6 chip flow co-design — **resolved 2026-06-12**: the Iris arc owns the BFF; Stage 3.6's chip round-trip executes as Iris Phase 3 Stage 3.1 in the real `agents/iris-bff` (see [`../iris/plan.md`](../iris/plan.md)).

## Aggregate progress

- [x] **Stage 3.1** — Proto extensions. **Landed 2026-06-20** (additive to the shipped `themis/v0.1.0` proto; contracts.md §1.2 reconciled). PR/merge pending.
- [x] **Stage 3.2** — `classifyIntentKind` + `detectMultiQuestion`. **Landed 2026-06-20** (rules-YAML + cheap-LLM tie-break; deterministic UD multi-question detector; wired into the production Koog graph; 91 themis tests green). PR/merge pending.
- [x] **Stage 3.3** — `routeToAgent` four-layer cascade. **Landed 2026-06-21** (CapabilitiesReadClient + fail-fast boot; Layers 0–3 over the JSON registry; routing wired into Resolution; 102 themis tests green). PR/merge pending.
- [x] **Stage 3.4** — Profile + `RefusalWithGaps` + corpus skeleton. **Landed 2026-06-21** (per-profile fuzzy/HITL knobs; STRICT-mode RefusalWithGaps via `collectBlockers`; `hitl` added to ResolveContext; resume-token Phase-3 fields; routing-corpus skeleton; 113 themis tests green). PR/merge pending.
- [ ] **Stage 3.5** — Eval harness + CI gates.
- [ ] **Stage 3.6** — Iris stub + observability + cutover.

When all six boxes above are checked, push tag `themis/v0.2.0`. Phase 3 closes the first kantheon-side arc.

## Up / across

- Up: [`./README.md`](./README.md) — Themis implementation index.
- Phase neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p2-overview.md`](./tasks-p2-overview.md).
- Co-design with: Iris BFF arc (separate planning arc to come).
