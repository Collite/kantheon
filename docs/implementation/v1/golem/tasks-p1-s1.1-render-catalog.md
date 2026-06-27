# Golem Phase 1 · Stage 1.1 — render catalog + retry/fallback core

> **Arc.** Golem Phase 1 (envelope-render lib — the format-catalog Koog spike, landed as a library).
> **Companions.** [`plan.md`](./plan.md) §3 Stage 1.1, [`../../../architecture/golem/architecture.md`](../../../architecture/golem/architecture.md) §3, [`../../../architecture/iris/contracts.md`](../../../architecture/iris/contracts.md) §1.1 (envelope/v1).
> **Branch.** `feat/golem-p1-s1.1-render-catalog`.
> **Goal.** Four render kinds as structured-output tools; retry + deterministic fallback proven on the gotcha fixtures.

## Porting source

The rewrite reproduces new-golem v2's **LLM-driven format catalog** (`tool_choice="any"`
over four `Render*` tools, Pydantic validation, retry, deterministic fallback). That
pipeline was deleted from ai-platform golem in commit `5281954d` ("Stage 8 … legacy
cutover") and survives at its parent `5281954d^`:

- `agents/golem/src/agent/format_catalog.py` — the four tool schemas + `infer_table_headers`.
- `agents/golem/src/agent/nodes.py` `_build_format_envelope` (:1095) / `_envelope_from_tool` (:1255)
  / `_deterministic_fallback_envelope` (:1210) — retry + fallback control flow.
- live `src/agent/nodes_v2/format.py` `_table_details_from_columns` (:223) — numeric directive emission.

**G-21…G-25 note.** The plan cites "G-21…G-25" gotchas from `golem/docs/v2/v2-overview.md`.
A full repo + git-history sweep found **no such catalog and no such file**. The five gotcha
**classes** are named directly in the plan (T2) and are distilled from the recovered Python
semantics — see [`shared/libs/kotlin/envelope-render/src/test/resources/fixtures/README.md`](../../../../shared/libs/kotlin/envelope-render/src/test/resources/fixtures/README.md).
Flagged to Bora; no blocker (the classes, not the labels, are load-bearing).

## Design decision — envelope-render stays LLM-agnostic

The deterministic core (header inference, column directives, retry state machine, fallback)
is **pure Kotlin with no LLM dependency** and is exhaustively unit-tested without one. The LLM
is injected as a pluggable `StructuredFormatter` interface; a Koog-backed implementation +
mock-executor test proves the spike. Rationale: the lib is consumed by Golem **and** Pythia —
hard-binding it to one gateway would be wrong. Maps cleanly onto the recovered Python
(`tool_choice="any"` → `StructuredFormatter.pick`, retry=2, deterministic fallback).

## Tasks

- [x] **T1 — module skeleton + build wiring.** `shared/libs/kotlin/envelope-render` with
  `java-library` + kotlin/serialization/ktlint; deps: `:shared:proto` (envelope/v1 + common/v1),
  kotlinx-serialization, Koog umbrella (adapter only), kotest + koog-agents-test (tests).
  `settings.gradle.kts` include added.
- [x] **T2 — gotcha fixtures.** Five classes distilled into `src/test/resources/fixtures/README.md`
  (chart-on-text-heavy-data, tool_choice-not-honoured, markdown re-parse trap, missing-headers,
  retry-exhaustion); expressed as scripted-formatter cases in `FormatCatalogSpec` (control-flow gotchas).
- [x] **T3 — `FormatCatalogSpec` (tests first).** All five gotcha classes + both fallback paths
  (table when structured / plaintext when not) against a scripted `StructuredFormatter`. 8 cases.
- [x] **T4 — catalog types + retry + deterministic fallback.** `RenderCall` sealed union
  (Plaintext/Markdown/Table/Chart) + input data classes; `FormatRequest`/`FormatResult`/`FormatToolException`;
  `FormatCatalog` orchestrator (retry `maxRetries+1`, repair-error feedback, deterministic fallback).
  Markdown carried verbatim; Chart carries intent only (Vega compile → Stage 1.2).
- [x] **T5 — header inference + directives + property tests.** `inferTableHeaders` (union of keys,
  first-appearance order, `title==name`) + `inferColumnDirectives` (right-align numerics, `%.2f`
  floats, integers raw). `HeaderInferenceSpec` (incl. idempotence/order-stability property) +
  `FormatDirectivesSpec`.
- [x] **T6 — provenance stamping (PD-9).** `FormatResult.toBlock(...)` stamps `Block.provenance`
  (view + producing agent id; callers enrich step/hypothesis/model refs; `source_tables` passthrough);
  absence yields no provenance field (not an error). `BlockAssemblerSpec`.
- [x] **T7 — Koog adapter + spike report.** `KoogStructuredFormatter` (Koog `PromptExecutor` +
  `prompt {}` DSL; discriminated-union JSON decoded by `RenderCallCodec`) + `FormatPrompt` (ported
  selection guidance). Mock-executor test via a scripted `PromptExecutor` (the Themis shape), incl.
  the retry-with-repair and LLM_ERROR→fallback paths. Spike verdict in module `README.md` (Koog
  handled the LLM call cleanly; `StructureFixingParser` deliberately not used — same conclusion Themis
  reached; retry/fallback belong in the library) — closes `golem-template-design.md` §10.

## DONE

Catalog suite green incl. all five gotcha classes; README spike verdict written.
**Status:** Stage 1.1 complete — T1–T7 landed, **33 tests green, ktlint clean**.
Carried to Stage 1.2: Vega-Lite compiler, chip/drilldown builders, `envelope-render/v0.1.0` tag.
