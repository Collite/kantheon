# Pythia — Design

Pythia is the autonomous analytical investigator: given a complex business question, it plans a multi-step investigation, executes it (with optional human-in-the-loop), and delivers a structured `InvestigationArtifact` — citable, replayable, multi-renderable. Hypothesis-driven planning is the default; procedural Q&A is the degenerate case.

Pythia answers cross-domain, RCA, forecast, and simulation questions. Per-domain procedural questions route to Golem instances instead.

## Files

| File | What |
|---|---|
| [`pythia-brief.md`](./pythia-brief.md) | Original brief: an autonomous analytical agent for complex Q&A, root-cause analysis, forecasting, simulation. The four use cases. |
| [`Pythia-v1-Design.md`](./Pythia-v1-Design.md) | The largest doc in the project. Comprehensive design: vision, platform placement, Investigation contract, worked examples (Nescafe-Maggi procedural + Private-channel RCA), internal subsystems (Plan Composer, DAG Executor, Hypothesis Evaluator, Budget Tracker, …), platform dependencies, roadmap, resolved decisions, glossary. **Read [`Pythia-Brainstorming.md`](./Pythia-Brainstorming.md) first.** |
| [`Pythia-Brainstorming.md`](./Pythia-Brainstorming.md) | Process record: the 2026-05-04 brainstorm. Captures the hypothesis-driven reframe (highest-leverage moment), the handle-table data model decision, capabilities-mcp emergence, parallel-execution-scheduler split, two-tier LLM strategy, budget tracker, stop conditions, renderer split. |
| [`Pythia-Brainstormin-cs.md`](./Pythia-Brainstormin-cs.md) | Czech translation/summary of the brainstorm (filename typo preserved). |
| [`framework-evaluation.md`](./framework-evaluation.md) | Evaluation of JVM agent frameworks (LangChain4j, Koog, Embabel, Spring AI) for Pythia's needs. Drove the "DAG executor custom; LLM orchestration via Koog" decision. |
| [`open-questions.md`](./open-questions.md) | Smaller questions needing decision before or shortly after v0 implementation. |

## What's elsewhere

- **Implementation architecture** lands under [`../../architecture/pythia/`](../../architecture/pythia/) when the Pythia arc starts.
- **v1.5 backlog** (explicitly deferred items): [`../../implementation/v1/pythia/v1.5-backlog.md`](../../implementation/v1/pythia/v1.5-backlog.md).
- The Pythia design's `Themis` dependency is at [`../themis/themis-design.md`](../themis/themis-design.md); `capabilities-mcp` is in the Themis arc plan.

## Up / across

- Up: [`../README.md`](../README.md) — design entry point.
- Across: [`../golem/`](../golem/) — Golem template; Pythia is the "master-of-Golems" when a cross-domain plan needs domain-curated knowledge. [`../themis/`](../themis/) — Pythia calls Themis at `INVESTIGATION_DEEP` profile.

## Vocabulary canon

- `query` (not "named query")
- `stack` (not "stackable pattern")
- `Shem` (the curated domain manifest per Golem pod)
- `Handle` (typed pointer to off-Pythia data)
- ShemManifest field names: `preferred_queries`, `counter_examples`, `preferred_capabilities`, `domain_entities`, `domain_terminology`, `description_for_router`, `example_questions`, `style_addendum`, `locale_defaults`.

Vocabulary sweep across Pythia-Brainstorming and Pythia-v1-Design for residual older terms is open (tracked in [`../../implementation/v1/_archive/next-steps.md`](../../implementation/v1/_archive/next-steps.md) §6).
