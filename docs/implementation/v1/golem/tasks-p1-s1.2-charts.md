# Golem Phase 1 · Stage 1.2 — charts (Vega-Lite compiler)

> **Arc.** Golem Phase 1 (envelope-render). **Branch.** `feat/golem-p1-s1.1-render-catalog` (Phase-1 branch).
> **Companions.** [`plan.md`](./plan.md) §3 Stage 1.2, [`tasks-p1-s1.1-render-catalog.md`](./tasks-p1-s1.1-render-catalog.md).

## Scope decision (2026-06-18, Bora) — was "charts + chips"

envelope-render stays a **generic** render library (catalog + tables + charts + provenance). The
**chip and drilldown builders move out** to the Golem arc (`agents/golem/.../chips` + `.../format`),
where their inputs live: the heuristic chips are domain content (Czech ERP column literals
`UCET_OBD` / `UCETNI_HODNOTA` / `KOD_STR` / `KOD_UCTU`), and pattern-derived chips + drilldowns need
PackageContext (pattern catalog, `drill_map`) and current bindings. Hosting them in a
constellation-wide lib (Pythia consumes it too) would drag Golem-domain types and ERP literals into
it. Relocated work is tracked under Golem Stage 3.1 (chips/drilldowns attached to the envelope).

## Tasks

- [x] **T1 — Vega-Lite parity specs (tests first).** `VegaLiteCompilerSpec` ports the Python test
  oracle (`tests/unit/test_vega_lite_compiler.py`): line/bar/pie/scatter/area × single/multi-series,
  stacking, `hide_series`, legend suppression, title, empty content. 11 cases.
- [x] **T2 — `VegaLiteCompiler`.** `ChartIntent` + rows → Vega-Lite v5 spec; behaviour-preserving port
  of `vega_lite_compiler.py`, cross-checked against agents-fe `compileVegaLite.ts` (non-empty paths
  byte-identical). Wired into `FormatCatalog.chartResult` (emits `ChartIntentDetails.vega_lite_spec_json`).
  One documented divergence: empty content follows Python's `_empty_spec` (degenerate/unreachable).
- [x] **Stage 2.1-review carry-in (folded in with Golem S2.4 T11, 2026-06-24).** `VegaLiteCompiler.compile` now guards an empty `y` series (`require`) and an out-of-domain `kind` (`else -> error`) — fail loud instead of rendering a blank spec.
- [ ] **T3 — tag `envelope-render/v0.1.0`.** After the Phase-1 PR merges to `main` (release action — Bora-authorised).

## DONE

Vega-Lite compiler green at Python/TS parity (44 tests total in the module, ktlint clean).
**Phase 1 deliverable complete pending the `envelope-render/v0.1.0` tag.**
