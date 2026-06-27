# Phase 2 — Resolver → Themis in kantheon, Koog-based

> **Reads with.** [`plan.md`](./plan.md) §4 (Phase 2 description), [`../../../architecture/themis/architecture.md`](../../../architecture/themis/architecture.md), [`../../../architecture/themis/contracts.md`](../../../architecture/themis/contracts.md), [`../../planning-conventions.md`](../../planning-conventions.md).
>
> **Phase deliverable.** `agents/themis` running in local K3s; Koog-based graph; eval gate green against the 50-question Stage 03 Czech corpus from `ai-platform/infra/nlp/eval/corpus/seed.jsonl`; quality equal-or-better than the ai-platform plain-coroutines Resolver baseline. Tag `themis/v0.1.0`.

## Stages

| Stage | Goal — testable boundary | Task list |
|---|---|---|
| **2.1** — Koog spike (go / no-go) | Spike report at `agents/themis/docs/koog-spike-report.md` with Bora's go/no-go decision; one deterministic node + one LLM-using node ported to Koog and tested against current Ktor | [`tasks-p2-s2.1-koog-spike.md`](./tasks-p2-s2.1-koog-spike.md) |
| **2.2** — Resolver extraction | `agents/themis` compiles in kantheon; all carried-over unit + mocked component tests pass (real-dependency integration verification deferred to the separate integration-test suite); proto package `org.tatrman.kantheon.themis.v1` | [`tasks-p2-s2.2-resolver-extraction.md`](./tasks-p2-s2.2-resolver-extraction.md) |
| **2.3** — Koog graph migration | `ThemisGraph` is a Koog `AIAgentGraphStrategy`; old `ResolverGraph` class deleted; existing test suite green | [`tasks-p2-s2.3-koog-migration.md`](./tasks-p2-s2.3-koog-migration.md) |
| **2.4** — Themis deploy + eval gate | `themis-mcp` pod Ready in local K3s; eval-gate quality ≥ ai-platform Resolver baseline on the 50-question Czech corpus | [`tasks-p2-s2.4-deploy-eval.md`](./tasks-p2-s2.4-deploy-eval.md) |

## Sequencing

Stage 2.1 (spike) gates Stage 2.3 (migration). Extraction (2.2) can begin in parallel with the spike — they touch disjoint surfaces — but the cutover from `ResolverGraph` to `ThemisGraph` only happens in 2.3 after the spike's go-decision.

```
Stage 2.1 (spike) ──────────────► Stage 2.3 (migration)
                                       ▲
Stage 2.2 (extraction) ────────────────┘
                                                       ↓
                                              Stage 2.4 (deploy + eval gate)
```

Suggested execution: run 2.1 and 2.2 on parallel branches; merge 2.2 first (lower risk); then 2.3 once the spike is closed.

## Pre-flight for the phase

- [ ] **Phase 1 DONE** — `capabilities-mcp/v0.1.0` tagged and deployed.
- [ ] **ai-platform `agents/resolver/` builds at HEAD** — verify `cd /Users/bora/Dev/ai-platform && just build-kt resolver` succeeds. (Should be true post-`gap-v1` PR #48; confirm.)
- [ ] **Bora available** to make the Stage 2.1 go/no-go decision on Koog adoption (~½ day window mid-phase).

## Aggregate progress

- [x] **Stage 2.1** — Koog spike report + decision (**GO**, 2026-05-29).
- [x] **Stage 2.2** — Resolver extracted to `agents/themis` (themis-mcp deploy verified on Rancher Desktop K3s).
- [x] **Stage 2.3** — Koog graph migration complete (T6 cutover: `Main.kt` uses `AIAgent.run`; `_koog-spike` removed).
- [x] **Stage 2.4** — Deploy + eval gate. **Closed by relocation (2026-06-20).** The fork switch-over (fork Stage 2.6) moved Themis's runtime onto the in-repo forked stack (Kadmos/Echo/Prometheus) and relocated the corpus eval no-regression gate into the **integration track** (repo-wide unit-tests-only policy). The original parity-vs-ai-platform-Resolver gate (T2/T5) is **superseded** — the Resolver comparand is being retired and Themis no longer calls any ai-platform service. `themis-mcp` builds, its overlay is wired to the forked stack, and it deploys via `just deploy-kt themis` (now in `deploy-fork`). The 50-question corpus eval now lives in the `themis-routing` nightly integration context (testing Stage 3.1, lands when Themis routing reaches the cluster).

All four stage boxes are checked → **Phase 2 closed at `themis/v0.1.0`**; Phase 3 (routing layer) unblocked.

## Up / across

- Up: [`./README.md`](./README.md) — Themis implementation index.
- Phase neighbours: [`tasks-p1-overview.md`](./tasks-p1-overview.md), [`tasks-p3-overview.md`](./tasks-p3-overview.md).
