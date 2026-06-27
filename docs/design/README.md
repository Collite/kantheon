# Design

"What we decided to build, and why." Each per-agent subfolder contains the agent's brief, design document, and brainstorming record. Briefs are the original-ask statement; design documents describe what the agent *is* as a service; brainstorming records capture how the design was reached, alternative paths considered, and where each decision was locked.

Read brainstorming → design (in that order) for any agent you're unfamiliar with.

## Per agent

- **[`iris/`](./iris/)** — user-facing FE + BFF (Vue 3 SPA + Kotlin/Ktor BFF). Conversation state, dispatch, slash-command UX, chip rendering, stream multiplexing.
- **[`themis/`](./themis/)** — routing + question-understanding (= Resolver post-extraction). Czech-first NLP, entity resolution, four-layer routing cascade, profile semantics, HITL via signed resume tokens.
- **[`pythia/`](./pythia/)** — autonomous analytical investigator. Hypothesis-driven planning, parallel DAG execution, scientific-method investigation for RCA / forecast / simulation.
- **[`golem/`](./golem/)** — parameterised per-domain Q&A template. One pod per Shem (Golem-ERP, Golem-HR, …); mini-plan executor; reused envelope contract with Pythia.
- **[`sysifos/`](./sysifos/)** — data-entry + data-management workbench for the Midas brokerage product (Vue 3 SPA + Kotlin/Ktor BFF). Forms-shaped sibling to Iris; manual transaction/balance entry, bulk grid, statement import, reconciliation; writes through Midas-core. Own arc (S1, 2026-06-13).

## Cross-cutting design content

Cross-cutting design lives at the top of this folder when the topic is genuinely about *what* we're building (constellation-wide) rather than the architecture. Today there are no cross-cutting design files at this level — the overall vision lives in [`../architecture/kantheon-architecture.md`](../architecture/kantheon-architecture.md), and per-agent design folders cover the rest.

## Naming conventions

| Pattern | Meaning |
|---|---|
| `<agent>-brief.md` | The original ask: a short, non-technical statement of why this agent exists. |
| `<agent>-design.md` | The locked design: what the agent *is* — surface, contract, lifecycle, principles. |
| `<agent>-brainstorming.md` | Process record: questions on the table, alternatives considered, decisions and where they locked. |
| `framework-evaluation.md`, `open-questions.md`, `v1.5-backlog.md` (Pythia) | Per-agent special files where the conversation produced standalone artefacts. |

## Reading order if you're new

1. `themis/themis-design.md` — the routing layer's surface; small enough to read end-to-end.
2. `iris/iris-design.md` — the user-facing surface.
3. `pythia/Pythia-v1-Design.md` — the largest doc; benefits from reading `Pythia-Brainstorming.md` first.
4. `golem/golem-template-design.md` — the per-domain template.

## Up / across

- Up: [`../README.md`](../README.md) — top-level docs index.
- Across: [`../architecture/`](../architecture/) — implementation architecture and contracts. [`../implementation/`](../implementation/) — phased plans and task lists.
