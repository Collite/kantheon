# envelope-render

The constellation format library: turns an agent's answer + result rows into
envelope/v1 `FormatSpec` / `Block`s. Consumed by **Golem** and **Pythia** (and
occasionally Themis). Ports new-golem v2's LLM-driven **format catalog** — four
`Render*` tools chosen with `tool_choice="any"`, with retry and a deterministic
fallback — into Kotlin.

> **Status.** Golem Phase 1 complete — Stage 1.1 (format-catalog Koog spike:
> render catalog + retry/fallback + header inference + provenance) and Stage 1.2
> (Vega-Lite compiler).
>
> **Scope boundary (2026-06-18).** envelope-render is a **generic** render library.
> Chip and drilldown builders are **not** here — they are domain-coupled (they need
> Golem's PackageContext, current bindings, and ERP column literals) and live in
> `agents/golem/.../chips` + `.../format`. Keeping them out preserves this lib's
> constellation-wide reusability (Pythia consumes it too).

## Layout

```
catalog/   RenderCall (4 kinds) + input shapes; RenderCallCodec; FormatPrompt;
           KoogStructuredFormatter (the LLM adapter)
fallback/  StructuredFormatter (the LLM boundary); FormatCatalog (retry + fallback)
tables/    inferTableHeaders; inferColumnDirectives
charts/    VegaLiteCompiler — ChartIntent + rows → Vega-Lite v5 spec
           (root) BlockAssembler — FormatResult.toBlock, stamps Block.provenance (PD-9)
```

## Design — LLM-agnostic by construction

The deterministic core (header inference, column directives, the retry state
machine, the fallback) is **pure Kotlin with no LLM dependency** and is fully
unit-tested without one. The LLM is injected as a one-method `StructuredFormatter`:

```kotlin
fun interface StructuredFormatter {
    suspend fun pick(request: FormatRequest, priorError: String?): RenderCall
}
```

`FormatCatalog` drives it up to `maxRetries + 1` times (default 2 retries → 3
attempts, matching v2's `BP_TEST_FORMAT_NODE_RETRIES`), feeding each failure into
the next attempt's repair prompt, and falls back deterministically when every
attempt fails: a **readable table** when the turn produced structured rows,
plaintext **only** when there is no structure at all. This is the load-bearing
v2 carve-out — a failed `/format chart` on id/code/name rows degrades to a table,
never a `str(dict)` dump.

Golem and Pythia provide their own gateway-backed `StructuredFormatter`s; the
bundled `KoogStructuredFormatter` is the reference implementation.

## Spike verdict — what Koog handled, what needed work

The Phase-1 brief was "validate Koog structured output for the four render kinds
before any template code" (architecture §9 risk row; `golem-template-design.md`
§10 open question). Findings:

- **Koog's `PromptExecutor` + `prompt {}` DSL are the clean integration points** —
  the same surface Themis settled on (`LlmGatewayPromptExecutor`). The adapter is
  a thin single-shot bridge: build the prompt, execute, decode the reply.
- **`StructureFixingParser` was deliberately NOT used.** It targets a single
  JSON-object schema, whereas the catalog is a four-way `tool_choice="any"`
  dispatch; and its built-in repair loop would duplicate `FormatCatalog`'s retry.
  Themis reached the same conclusion independently (its output was a JSON array,
  not an object). The discriminated-union object (`{"tool": "...", ...}`) decoded
  by `RenderCallCodec` reproduces v2's tool-choice + Pydantic-error taxonomy
  (`no_tool_call` / `unknown_tool` / `pydantic_invalid → SCHEMA_INVALID`) without it.
- **The right boundary is retry+fallback in the library, not in Koog.** Keeping
  the orchestration LLM-agnostic makes the five gotcha behaviours testable with a
  scripted formatter (no live model, no flaky mock), and lets Golem and Pythia
  reuse the exact same fallback discipline behind different gateways.

Net: Koog covers the LLM call cleanly; the format-catalog *value* (header
inference, the retry/fallback state machine, deterministic degradation) is
library logic that Koog neither provides nor constrains.

## Gotcha fixtures

The five gotcha classes (`src/test/resources/fixtures/README.md`) are covered as
control-flow cases in `FormatCatalogSpec`. The plan's cited "G-21…G-25 /
`golem/docs/v2/v2-overview.md`" source does **not** exist in ai-platform or its
git history; the classes are distilled from the plan + the recovered Python
(`format_catalog.py` / `nodes.py` @ git `5281954d^`).

## Tests

`./gradlew :shared:libs:kotlin:envelope-render:test` — 44 tests (catalog 8,
codec 8, Koog adapter 3, header inference 5, directives 6, provenance 3,
Vega-Lite compiler 11).
